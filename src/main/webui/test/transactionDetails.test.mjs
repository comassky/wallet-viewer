import assert from 'node:assert/strict';
import test from 'node:test';
import { effectScope, isProxy } from 'vue';
import { walletApi } from '../src/services/walletApi.ts';
import { useTransactionDetails } from '../src/composables/useTransactionDetails.ts';

function fixture(t, request) {
  const original = walletApi.transactionDetails;
  walletApi.transactionDetails = request;
  const scope = effectScope();
  t.after(() => { scope.stop(); walletApi.transactionDetails = original; });
  return { scope, state: scope.run(useTransactionDetails) };
}

test('loads details and clears previous errors on retry', async t => {
  let fails = true;
  const { state } = fixture(t, async txid => { if (fails) throw new Error('offline'); return { txid }; });
  await state.load('a');
  assert.equal(state.error.value, 'offline');
  assert.equal(state.loading.value, false);
  fails = false;
  await state.load('a');
  assert.equal(state.details.value.txid, 'a');
  assert.equal(state.error.value, null);
});

test('switching transaction cancels previous request and ignores its late response', async t => {
  const requests = [];
  const { state } = fixture(t, (txid, { signal }) => new Promise(resolve => requests.push({ txid, signal, resolve })));
  const first = state.load('a');
  const second = state.load('b');
  assert.equal(requests[0].signal.aborted, true);
  requests[1].resolve({ txid: 'b' });
  await second;
  requests[0].resolve({ txid: 'a' });
  await first;
  assert.equal(state.details.value.txid, 'b');
});

test('close and disposal cancel work without publishing errors or late data', async t => {
  let finish;
  let signal;
  const { state, scope } = fixture(t, (_txid, options) => { signal = options.signal; return new Promise(resolve => { finish = resolve; }); });
  const pending = state.load('a');
  state.cancel();
  assert.equal(signal.aborted, true);
  finish({ txid: 'a' });
  await pending;
  assert.equal(state.details.value, null);
  assert.equal(state.loading.value, false);
  const disposed = state.load('b');
  scope.stop();
  assert.equal(signal.aborted, true);
  finish({ txid: 'b' });
  await disposed;
  assert.equal(state.details.value, null);
});

test('mismatched transaction data is never displayed', async t => {
  const { state } = fixture(t, async () => ({ txid: 'wrong' }));
  await state.load('expected');
  assert.equal(state.details.value, null);
  assert.match(state.error.value, /does not match/);
});

test('detail snapshots keep their identity without deep reactive proxies', async t => {
  const snapshot = { txid: 'a', inputs: [{ value: 42 }], outputs: [] };
  const { state } = fixture(t, async () => snapshot);
  await state.load('a');
  assert.equal(state.details.value, snapshot);
  assert.equal(isProxy(state.details.value.inputs), false);
});