import assert from 'node:assert/strict';
import test from 'node:test';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { requestJson } from '../src/services/http.ts';
import { walletApi } from '../src/services/walletApi.ts';
import { parseWalletEnvelope } from '../src/services/walletStream.ts';

const balance = { confirmed: 50, unconfirmed: -8, total: 42 };
const receive = { index: 0, address: 'bc1-test', path: 'm/0/0' };
const transaction = { txid: 'tx', amount: -8, received: 0, sent: 8, height: -1, confirmations: 0, timestamp: null, type: 'sent', addresses: ['bc1-test'] };
const utxo = { txid: 'tx', vout: 0, value: 50, height: 0, confirmations: 0, address: 'bc1-test' };
const snapshot = { balance, receiveAddress: receive, transactions: [transaction], utxos: [utxo] };
const server = { host: 'localhost', port: 50002, tls: true, connected: false, serverVersion: null, protocolVersion: null };
const prices = { eur: 60_000, usd: 70_000, timestamp: 1 };
const details = { txid: 'tx', version: 2, lockTime: 0, size: 100,
  inputs: [{ txid: null, vout: 0, address: null, value: null, coinbase: true }],
  outputs: [{ index: 0, address: null, value: 50, scriptHex: '' }], totalInput: null, totalOutput: 50, fee: null };

test('wallet service uses fetch with endpoint, JSON header, composed cancellation and timer cleanup', async t => {
  const controller = new AbortController();
  const timer = t.mock.method(globalThis, 'setTimeout');
  const clear = t.mock.method(globalThis, 'clearTimeout');
  const compose = t.mock.method(AbortSignal, 'any');
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/wallet');
    assert.equal(options.headers.Accept, 'application/json');
    assert.equal(options.signal.aborted, false);
    return new Response(JSON.stringify(snapshot));
  });
  assert.deepEqual(await walletApi.snapshot({ signal: controller.signal }), snapshot);
  assert.ok(timer.mock.calls.some(call => call.arguments[1] === 120_000));
  assert.ok(clear.mock.calls.some(call => call.arguments[0] === timer.mock.calls[0].result));
  assert.ok(compose.mock.calls.some(call => call.arguments[0].includes(controller.signal)));
  assert.equal(walletApi.qrAtUrl(7), '/api/wallet/receive/7/qr');
});

test('server metadata timeout override does not alter other endpoint defaults', async t => {
  const requests = [];
  const timer = t.mock.method(globalThis, 'setTimeout');
  const responses = [server, prices, details];
  t.mock.method(globalThis, 'fetch', async url => { requests.push(url); return new Response(JSON.stringify(responses.shift())); });
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

test('caller cancellation aborts an in-flight fetch request and clears its deadline', async t => {
  let received;
  const ready = new Promise(resolve => { received = resolve; });
  const url = await localServer(t, () => received());
  const controller = new AbortController();
  const timer = t.mock.method(globalThis, 'setTimeout');
  const clear = t.mock.method(globalThis, 'clearTimeout');
  const pending = requestJson(url, { signal: controller.signal });
  const rejected = assert.rejects(pending, { name: 'AbortError' });
  await ready;
  controller.abort();
  await rejected;
  const deadline = timer.mock.calls.find(call => call.arguments[1] === 120_000).result;
  assert.ok(clear.mock.calls.some(call => call.arguments[0] === deadline));
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

test('all REST endpoints accept their contract and reject a malformed successful response', async t => {
  const fees = { fastest: 2.5, halfHour: 2, hour: 1, economy: 1, minimum: 1, timestamp: 1 };
  const point = { time: 0, balanceSats: 42, valueEur: null, valueUsd: null, priceEur: null, priceUsd: null, priceTime: null, priceStale: true };
  const signedPoint = { ...point, balanceSats: -42, valueEur: -0.01, valueUsd: -0.02, priceEur: 60000, priceUsd: 70000, priceTime: 0 };
  const check = { address: 'other', belongs: false, chain: null, index: null, path: null, checked: 20 };
  const cases = [
    [walletApi.server, server], [walletApi.prices, prices], [walletApi.fees, fees],
    [walletApi.balanceHistory, [point, signedPoint]], [walletApi.snapshot, snapshot], [walletApi.balance, balance],
    [walletApi.transactions, [transaction]], [() => walletApi.transactionDetails('tx'), details],
    [walletApi.utxos, [utxo]], [walletApi.receive, receive], [() => walletApi.receiveAt(0), receive],
    [() => walletApi.verifyAddress('other'), check],
  ];
  let payload;
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify(payload)));
  for (const [load, valid] of cases) {
    payload = valid;
    assert.deepEqual(await load(), valid);
    for (const invalid of [null, {}, 'private-invalid-response']) {
      payload = invalid;
      await assert.rejects(load(), { message: 'The server returned invalid data. Please try again.' });
    }
  }
});

test('nested snapshot validation is shared between REST and WebSocket', async t => {
  let payload;
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify(payload)));
  const mutations = [
    value => { value.balance.total = Number.MAX_SAFE_INTEGER + 1; },
    value => { value.transactions[0].amount = 0.5; },
    value => { value.transactions[0].timestamp = 'yesterday'; },
    value => { value.transactions[0].addresses = [null]; },
    value => { value.transactions[0].type = 'unknown'; },
    value => { delete value.utxos[0].address; },
    value => { value.receiveAddress.index = -1; },
    value => { value.discovery = { complete: true, addressLimit: 1, gapLimit: 1, receiveScanned: 2, changeScanned: 1 }; },
  ];
  for (const mutate of mutations) {
    payload = structuredClone(snapshot);
    mutate(payload);
    await assert.rejects(walletApi.snapshot(), /server returned invalid data/);
    assert.equal(parseWalletEnvelope(JSON.stringify({ version: 1, status: 'live', message: null, snapshot: payload })), null);
  }
});

test('REST rejects invalid nested details, non-finite prices and missing history metadata', async t => {
  let payload;
  t.mock.method(globalThis, 'fetch', async () => new Response(payload));
  for (const mutate of [
    value => { value.inputs[0].value = '50'; },
    value => { value.outputs[0].index = -1; },
    value => { value.fee = -1; },
    value => { delete value.outputs[0].scriptHex; },
  ]) {
    const invalid = structuredClone(details);
    mutate(invalid);
    payload = JSON.stringify(invalid);
    await assert.rejects(walletApi.transactionDetails('tx'), /server returned invalid data/);
  }
  payload = '{"eur":1e999,"usd":70000,"timestamp":1}';
  await assert.rejects(walletApi.prices(), /server returned invalid data/);
  payload = '[{"time":0,"balanceSats":42,"valueEur":null,"valueUsd":null}]';
  await assert.rejects(walletApi.balanceHistory(), /server returned invalid data/);
});
