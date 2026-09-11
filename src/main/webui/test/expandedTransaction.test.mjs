import assert from 'node:assert/strict';
import test from 'node:test';
import { effectScope, nextTick, ref } from 'vue';
import { walletApi } from '@/services/walletApi.ts';
import { useExpandedTransaction } from '@/composables/useExpandedTransaction.ts';

function fixture(t) {
  const original = walletApi.transactionDetails;
  const requests = [];
  walletApi.transactionDetails = (txid, { signal }) => new Promise((resolve, reject) => {
    requests.push({ txid, signal, resolve, reject });
  });
  const transactions = ref([{ txid: 'a', confirmations: 0 }, { txid: 'b', confirmations: 1 }]);
  const scope = effectScope();
  const state = scope.run(() => useExpandedTransaction(transactions));
  t.after(() => { scope.stop(); walletApi.transactionDetails = original; });
  return { state, transactions, requests, scope };
}

test('details load only when expanded and collapsing cancels pending work', async t => {
  const { state, requests } = fixture(t);
  assert.equal(state.expandedTxid.value, null);
  assert.equal(requests.length, 0);
  state.toggle('a');
  assert.equal(state.expandedTxid.value, 'a');
  assert.equal(state.loading.value, true);
  assert.equal(requests.length, 1);
  state.toggle('a');
  assert.equal(state.expandedTxid.value, null);
  assert.equal(requests[0].signal.aborted, true);
  requests[0].resolve({ txid: 'a' });
  await nextTick();
  assert.equal(state.details.value, null);
  assert.equal(state.loading.value, false);
});

test('expanding another row replaces the selection and ignores stale data', async t => {
  const { state, requests } = fixture(t);
  state.toggle('a');
  state.toggle('b');
  assert.equal(state.expandedTxid.value, 'b');
  assert.equal(requests[0].signal.aborted, true);
  requests[1].resolve({ txid: 'b' });
  await nextTick();
  requests[0].resolve({ txid: 'a' });
  await nextTick();
  assert.equal(state.details.value.txid, 'b');
});

test('live updates preserve the expanded row without reloading; removal collapses it', async t => {
  const { state, transactions, requests } = fixture(t);
  state.toggle('a');
  transactions.value = [{ txid: 'b', confirmations: 2 }, { txid: 'a', confirmations: 1 }];
  await nextTick();
  assert.equal(state.expandedTxid.value, 'a');
  assert.equal(requests.length, 1);
  transactions.value = [{ txid: 'b', confirmations: 2 }];
  await nextTick();
  assert.equal(state.expandedTxid.value, null);
  assert.equal(requests[0].signal.aborted, true);
});

test('retry reloads the expanded transaction and does nothing after collapse', async t => {
  const { state, requests } = fixture(t);
  state.toggle('a');
  requests[0].reject(new Error('offline'));
  await nextTick();
  assert.equal(state.error.value, 'offline');
  state.retry();
  assert.equal(requests.length, 2);
  assert.equal(requests[1].txid, 'a');
  assert.equal(state.error.value, null);
  requests[1].resolve({ txid: 'a' });
  await nextTick();
  assert.equal(state.details.value.txid, 'a');
  state.toggle('a');
  state.retry();
  assert.equal(requests.length, 2);
});

test('disposing the section cancels the expanded transaction request', t => {
  const { state, scope, requests } = fixture(t);
  state.toggle('a');
  scope.stop();
  assert.equal(requests[0].signal.aborted, true);
});