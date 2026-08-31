import assert from 'node:assert/strict';
import test from 'node:test';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { AxiosError } from 'axios';
import { httpClient, requestJson } from '../src/services/http.ts';
import { walletApi } from '../src/services/walletApi.ts';

function adapter(t, handler) {
  const original = httpClient.defaults.adapter;
  httpClient.defaults.adapter = handler;
  t.after(() => { httpClient.defaults.adapter = original; });
}

function response(config, data) {
  return { data, status: 200, statusText: 'OK', headers: {}, config };
}

test('wallet service uses Axios with endpoint, JSON header, default timeout and supplied signal', async t => {
  const snapshot = { balance: { total: 42 } };
  const controller = new AbortController();
  adapter(t, async config => {
    assert.equal(config.url, '/api/wallet');
    assert.equal(config.method, 'get');
    assert.equal(config.headers.get('Accept'), 'application/json');
    assert.equal(config.timeout, 120_000);
    assert.equal(config.signal, controller.signal);
    assert.equal(config.responseType, 'json');
    return response(config, JSON.stringify(snapshot));
  });
  assert.deepEqual(await walletApi.snapshot({ signal: controller.signal }), snapshot);
  assert.equal(walletApi.qrAtUrl(7), '/api/wallet/receive/7/qr');
});

test('server metadata timeout override does not alter other endpoint defaults', async t => {
  const requests = [];
  adapter(t, async config => { requests.push([config.url, config.timeout]); return response(config, '{}'); });
  await walletApi.server({ timeoutMs: 5000 });
  await walletApi.prices();
  await walletApi.transactionDetails('id/with spaces');
  assert.deepEqual(requests, [
    ['/api/wallet/server', 5000], ['/api/wallet/prices', 120_000],
    ['/api/wallet/transactions/id%2Fwith%20spaces', 120_000],
  ]);
});

test('network failures remain readable and are not retried automatically', async t => {
  let calls = 0;
  adapter(t, async config => { calls++; throw new AxiosError('offline', 'ERR_NETWORK', config); });
  await assert.rejects(requestJson('/test'), /Network unreachable: offline/);
  assert.equal(calls, 1);
});

test('an already cancelled caller sends no request and preserves its abort reason', async t => {
  adapter(t, () => { assert.fail('Cancelled request must not reach the adapter'); });
  const controller = new AbortController();
  const reason = new Error('No longer selected');
  controller.abort(reason);
  await assert.rejects(requestJson('/test', { signal: controller.signal }), error => error === reason);
});

test('invalid timeout overrides cannot disable the deadline', async t => {
  adapter(t, () => { assert.fail('Invalid timeout must not send a request'); });
  for (const timeoutMs of [0, -1, Infinity, NaN]) {
    await assert.rejects(requestJson('/test', { timeoutMs }), RangeError);
  }
});

// Exercise a real Axios transport against loopback: no external API or personal wallet.
async function localServer(t, handler) {
  const server = createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  const original = httpClient.defaults.proxy;
  httpClient.defaults.proxy = false;
  t.after(() => { httpClient.defaults.proxy = original; });
  return `http://127.0.0.1:${server.address().port}`;
}

test('real Axios transport decodes JSON and reports text, JSON and empty HTTP failures', async t => {
  const url = await localServer(t, (req, res) => {
    if (req.url === '/text') { res.writeHead(503); res.end('Unavailable'); }
    else if (req.url === '/json') { res.writeHead(400, { 'Content-Type': 'application/json' }); res.end('{"message":"Bad input"}'); }
    else if (req.url === '/empty') { res.writeHead(404); res.end(); }
    else { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"ok":true}'); }
  });
  assert.deepEqual(await requestJson(url), { ok: true });
  await assert.rejects(requestJson(`${url}/text`), /HTTP 503 — Unavailable/);
  await assert.rejects(requestJson(`${url}/json`), /HTTP 400 — .*Bad input/);
  await assert.rejects(requestJson(`${url}/empty`), /^Error: HTTP 404$/);
});

test('malformed successful JSON is rejected rather than published as wallet data', async t => {
  const url = await localServer(t, (_req, res) => { res.writeHead(200); res.end('not json'); });
  await assert.rejects(requestJson(url), /server returned invalid JSON/);
});

test('caller cancellation aborts an in-flight Axios request and removes its listener', async t => {
  let received;
  const ready = new Promise(resolve => { received = resolve; });
  const url = await localServer(t, () => received());
  const controller = new AbortController();
  const remove = t.mock.method(controller.signal, 'removeEventListener');
  const pending = requestJson(url, { signal: controller.signal });
  const rejected = assert.rejects(pending, { name: 'AbortError' });
  await ready;
  controller.abort();
  await rejected;
  assert.ok(remove.mock.calls.some(call => call.arguments[0] === 'abort'));
});

test('Axios enforces an overridden timeout with the existing user-facing message', async t => {
  const url = await localServer(t, () => {});
  await assert.rejects(requestJson(url, { timeoutMs: 50 }), /The server took too long to respond/);
});
