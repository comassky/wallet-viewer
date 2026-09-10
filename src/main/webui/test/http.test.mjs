import assert from 'node:assert/strict';
import test from 'node:test';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { requestJson } from '../src/services/http.ts';
import { walletApi } from '../src/services/walletApi.ts';

test('wallet service uses fetch with endpoint, JSON header, default timeout and cancellation cleanup', async t => {
  const snapshot = { balance: { total: 42 } };
  const controller = new AbortController();
  const timer = t.mock.method(globalThis, 'setTimeout');
  const clear = t.mock.method(globalThis, 'clearTimeout');
  const remove = t.mock.method(controller.signal, 'removeEventListener');
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/wallet');
    assert.equal(options.headers.Accept, 'application/json');
    assert.equal(options.signal.aborted, false);
    return new Response(JSON.stringify(snapshot));
  });
  assert.deepEqual(await walletApi.snapshot({ signal: controller.signal }), snapshot);
  assert.ok(timer.mock.calls.some(call => call.arguments[1] === 120_000));
  assert.ok(clear.mock.calls.some(call => call.arguments[0] === timer.mock.calls[0].result));
  assert.ok(remove.mock.calls.some(call => call.arguments[0] === 'abort'));
  assert.equal(walletApi.qrAtUrl(7), '/api/wallet/receive/7/qr');
});

test('server metadata timeout override does not alter other endpoint defaults', async t => {
  const requests = [];
  const timer = t.mock.method(globalThis, 'setTimeout');
  t.mock.method(globalThis, 'fetch', async url => { requests.push(url); return new Response('{}'); });
  await walletApi.server({ timeoutMs: 5000 });
  await walletApi.prices();
  await walletApi.transactionDetails('id/with spaces');
  assert.deepEqual(requests, [
    '/api/wallet/server', '/api/wallet/prices', '/api/wallet/transactions/id%2Fwith%20spaces',
  ]);
  assert.deepEqual(timer.mock.calls.map(call => call.arguments[1]), [5000, 120_000, 120_000]);
});

test('network failures remain readable and are not retried automatically', async t => {
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async () => { calls++; throw new TypeError('offline'); });
  await assert.rejects(requestJson('/test'), /Network unreachable: offline/);
  assert.equal(calls, 1);
});

test('an already cancelled caller sends no request and preserves its abort reason', async t => {
  t.mock.method(globalThis, 'fetch', () => { assert.fail('Cancelled request must not reach fetch'); });
  const controller = new AbortController();
  const reason = new Error('No longer selected');
  controller.abort(reason);
  await assert.rejects(requestJson('/test', { signal: controller.signal }), error => error === reason);
});

test('invalid timeout overrides cannot disable the deadline', async t => {
  t.mock.method(globalThis, 'fetch', () => { assert.fail('Invalid timeout must not send a request'); });
  for (const timeoutMs of [0, -1, Infinity, NaN]) {
    await assert.rejects(requestJson('/test', { timeoutMs }), RangeError);
  }
});

async function localServer(t, handler) {
  const server = createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  return `http://127.0.0.1:${server.address().port}`;
}

test('real fetch transport decodes JSON and reports text, JSON and empty HTTP failures', async t => {
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

test('caller cancellation aborts an in-flight fetch request and removes its listener', async t => {
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

test('fetch enforces an overridden timeout with the existing user-facing message', async t => {
  const url = await localServer(t, () => {});
  await assert.rejects(requestJson(url, { timeoutMs: 50 }), /The server took too long to respond/);
});

test('deadline includes reading a slow response body', async t => {
  const url = await localServer(t, (_req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.flushHeaders();
    res.write('{');
  });
  await assert.rejects(requestJson(url, { timeoutMs: 100 }), /The server took too long to respond/);
});

test('cancellation while reading the response body preserves the caller reason', async t => {
  let received;
  const ready = new Promise(resolve => { received = resolve; });
  const url = await localServer(t, (_req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.flushHeaders();
    res.write('{');
    received();
  });
  const controller = new AbortController();
  const reason = new Error('Selection changed');
  const rejected = assert.rejects(requestJson(url, { signal: controller.signal }), error => error === reason);
  await ready;
  controller.abort(reason);
  await rejected;
});
