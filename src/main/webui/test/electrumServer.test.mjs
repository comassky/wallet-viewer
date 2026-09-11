import assert from 'node:assert/strict';
import test from 'node:test';
import { effectScope } from 'vue';
import { walletApi } from '@/services/walletApi.ts';
import { useElectrumServer } from '@/composables/useElectrumServer.ts';

const server = { host: 'localhost', port: 50002, tls: true, connected: true, serverVersion: 'electrs 1.0', protocolVersion: '1.4' };
function fixture(t, request) {
  const original = walletApi.server;
  walletApi.server = request;
  const scope = effectScope();
  t.after(() => { scope.stop(); walletApi.server = original; });
  return { scope, state: scope.run(useElectrumServer) };
}

test('metadata is fetched only on demand and duplicate hover/focus loads are coalesced', async t => {
  let finish;
  let calls = 0;
  const { state } = fixture(t, options => {
    calls++;
    assert.equal(options.timeoutMs, 5000);
    return new Promise(resolve => { finish = resolve; });
  });
  assert.equal(calls, 0);
  const pending = state.load();
  await state.load();
  assert.equal(calls, 1);
  assert.equal(state.loading.value, true);
  finish(server);
  await pending;
  assert.deepEqual(state.server.value, server);
  assert.equal(state.loading.value, false);
});

test('connection changes clear metadata, abort the request and ignore old socket results', async t => {
  const requests = [];
  const { state } = fixture(t, ({ signal }) => new Promise(resolve => requests.push({ signal, resolve })));
  const old = state.load();
  state.reset();
  assert.equal(requests[0].signal.aborted, true);
  const current = state.load();
  const changed = { ...server, serverVersion: 'electrs 2.0' };
  requests[1].resolve(changed);
  await current;
  requests[0].resolve(server);
  await old;
  assert.deepEqual(state.server.value, changed);
  state.reset();
  assert.equal(state.server.value, null);
});

test('errors are generic, retriable and cannot reveal backend response content', async t => {
  let failing = true;
  const { state } = fixture(t, async () => {
    if (failing) throw new Error('internal/private server failure');
    return { ...server, serverVersion: null, protocolVersion: null };
  });
  await state.load();
  assert.equal(state.error.value, 'Server information unavailable.');
  assert.equal(state.server.value, null);
  failing = false;
  await state.load();
  assert.equal(state.error.value, null);
  assert.equal(state.server.value.serverVersion, null);
});

test('disposal cancels outstanding work and prevents further metadata loads', async t => {
  let signal;
  let finish;
  let calls = 0;
  const { scope, state } = fixture(t, options => {
    calls++;
    signal = options.signal;
    return new Promise(resolve => { finish = resolve; });
  });
  const pending = state.load();
  scope.stop();
  assert.equal(signal.aborted, true);
  finish(server);
  await pending;
  await state.load();
  assert.equal(calls, 1);
  assert.equal(state.server.value, null);
});