import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import * as Vue from 'vue';
import { compile } from '@vue/compiler-dom';
import { parse } from '@vue/compiler-sfc';
import { renderToString } from '@vue/server-renderer';
import { effectScope, ref } from 'vue';
import { transactionPageSize, useTransactionList } from '../src/composables/useTransactionList.ts';

test('confirmation status shows a static pending badge at zero and spins only from one to four', async () => {
  const source = readFileSync(new URL('../src/components/ConfirmationStatus.vue', import.meta.url), 'utf8');
  const { descriptor } = parse(source);
  const { code } = compile(descriptor.template.content, { mode: 'function', prefixIdentifiers: true });
  const component = { props: ['confirmations', 'compact'], render: new Function('Vue', code)(Vue) };
  for (const compact of [false, true]) {
    for (const confirmations of [0, 1, 4, 5, 6]) {
      const html = await renderToString(Vue.createSSRApp(component, { confirmations, compact }));
      assert.equal(html.includes('Pending'), confirmations === 0);
      assert.equal(html.includes('animate-spin'), confirmations >= 1 && confirmations < 5);
      if (confirmations === 0) assert.ok(html.includes('border-amber-400/20'));
      else assert.match(html, new RegExp(`>\\s*${confirmations}\\s*<`));
    }
  }
});

const columns = [{ key: 'amount', label: 'Amount', value: tx => tx.amount }];
function fixture(t, transactions) {
  const scope = effectScope();
  t.after(() => scope.stop());
  const rows = ref(transactions);
  return { rows, state: scope.run(() => useTransactionList(rows, columns)) };
}

test('filters include self transfers in all and every unconfirmed type in pending', t => {
  const { state } = fixture(t, [
    { txid: 'a', type: 'received', confirmations: 1 },
    { txid: 'b', type: 'sent', confirmations: 0 },
    { txid: 'c', type: 'self', confirmations: 0 },
    { txid: 'd', type: 'received', confirmations: 0 },
  ]);
  assert.deepEqual(state.counts.value, { all: 4, received: 2, sent: 1, pending: 3 });
  state.filter.value = 'received';
  assert.deepEqual(state.visible.value.map(tx => tx.txid), ['a', 'd']);
  state.filter.value = 'sent';
  assert.deepEqual(state.visible.value.map(tx => tx.txid), ['b']);
  state.filter.value = 'pending';
  assert.deepEqual(state.visible.value.map(tx => tx.txid), ['b', 'c', 'd']);
  state.resetFilters();
  assert.equal(state.visible.value.length, 4);
});

test('search trims and ignores case, combines with filters and can be reset', t => {
  const { state } = fixture(t, [
    { txid: 'aaBBcc', type: 'received', confirmations: 1 },
    { txid: 'ddBBee', type: 'sent', confirmations: 0 },
  ]);
  state.query.value = '  BB  ';
  assert.equal(state.filtered.value.length, 2);
  state.filter.value = 'sent';
  assert.equal(state.visible.value[0].txid, 'ddBBee');
  state.query.value = 'not-found';
  assert.equal(state.visible.value.length, 0);
  state.resetFilters();
  assert.equal(state.filter.value, 'all');
  assert.equal(state.query.value, '');
  assert.equal(state.filtered.value.length, 2);
});

test('sort precedes pagination and browsing changes reset the page size without mutating data', t => {
  const original = Array.from({ length: 63 }, (_, index) => ({ txid: `${index}`, amount: 63 - index, type: 'received', confirmations: 1 }));
  const { rows, state } = fixture(t, original);
  assert.equal(state.visible.value.length, transactionPageSize);
  state.showMore();
  assert.equal(state.visible.value.length, 50);
  state.toggleSort('amount');
  assert.equal(state.visible.value.length, 25);
  assert.equal(state.visible.value[0].amount, 1);
  assert.deepEqual(rows.value, original);
  state.showMore();
  state.showMore();
  assert.equal(state.visible.value.length, 63);
  assert.equal(state.hasMore.value, false);
  state.query.value = '1';
  state.resetFilters();
  assert.equal(state.visible.value.length, 25);
  state.showMore();
  state.descending.value = true;
  assert.equal(state.visible.value.length, 25);
  assert.equal(state.visible.value[0].amount, 63);
});

test('live snapshots retain filter, query, sort and number of expanded rows', t => {
  const original = Array.from({ length: 70 }, (_, index) => ({ txid: `ab${index}`, amount: index, type: 'received', confirmations: 0 }));
  const { rows, state } = fixture(t, original);
  state.query.value = 'ab';
  state.filter.value = 'pending';
  state.toggleSort('amount');
  state.showMore();
  rows.value = [{ txid: 'ab-new', amount: -1, type: 'sent', confirmations: 0 }, ...original];
  assert.equal(state.visible.value.length, 50);
  assert.equal(state.visible.value[0].txid, 'ab-new');
  assert.equal(state.query.value, 'ab');
  assert.equal(state.filter.value, 'pending');
  assert.equal(state.sortKey.value, 'amount');
  rows.value = rows.value.map(tx => ({ ...tx, confirmations: 1 }));
  assert.equal(state.filtered.value.length, 0);
  assert.equal(state.hasMore.value, false);
});