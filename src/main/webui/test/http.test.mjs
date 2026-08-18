import assert from 'node:assert/strict';
import test from 'node:test';
import { requestJson } from '../src/services/http.ts';
import { walletApi } from '../src/services/walletApi.ts';

test('wallet service supplies endpoint, JSON header and cancellation signal', async t => {
  const snapshot = { balance: { total: 42 } };
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/wallet');
    assert.equal(options.headers.Accept, 'application/json');
    assert.ok(options.signal instanceof AbortSignal);
    return Response.json(snapshot);
  });
  assert.deepEqual(await walletApi.snapshot(), snapshot);
  assert.equal(walletApi.qrAtUrl(7), '/api/wallet/receive/7/qr');
});

test('HTTP and network failures remain readable', async t => {
  const fetch = t.mock.method(globalThis, 'fetch', async () => new Response('Unavailable', { status: 503 }));
  await assert.rejects(requestJson('/test'), /HTTP 503 — Unavailable/);
  fetch.mock.mockImplementation(async () => { throw new TypeError('offline'); });
  await assert.rejects(requestJson('/test'), /Network unreachable: offline/);
});

test('caller cancellation propagates and abort listeners are removed', async t => {
  const controller = new AbortController();
  const remove = t.mock.method(controller.signal, 'removeEventListener');
  t.mock.method(globalThis, 'fetch', (_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), { once: true });
  }));
  const request = requestJson('/test', { signal: controller.signal });
  controller.abort();
  await assert.rejects(request, { name: 'AbortError' });
  assert.equal(remove.mock.callCount(), 1);
});

test('request timeout aborts transport with an English error', async t => {
  let timeout;
  t.mock.method(globalThis, 'setTimeout', callback => { timeout = callback; return 1; });
  const clear = t.mock.method(globalThis, 'clearTimeout', () => {});
  t.mock.method(globalThis, 'fetch', (_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), { once: true });
  }));
  const request = requestJson('/test');
  timeout();
  await assert.rejects(request, /The server took too long to respond/);
  assert.equal(clear.mock.callCount(), 1);
});