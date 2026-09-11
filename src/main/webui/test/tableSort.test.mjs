import assert from 'node:assert/strict';
import test from 'node:test';
import { ref } from 'vue';
import { useTableSort } from '@/composables/useTableSort.ts';

const columns = [
  { key: 'amount', label: 'Amount', value: row => row.amount },
  { key: 'date', label: 'Date', value: row => row.date },
  { key: 'outpoint', label: 'Outpoint', value: row => row.txid, secondary: row => row.vout },
];

test('native table sorting is stable and never mutates frozen input', () => {
  const values = Object.freeze([{ id: 'first', amount: 2 }, { id: 'second', amount: 2 }, { id: 'third', amount: 1 }]);
  const state = useTableSort(ref(values), columns);
  state.toggleSort('amount');
  assert.deepEqual(state.sorted.value.map(value => value.id), ['third', 'first', 'second']);
  assert.deepEqual(values.map(value => value.id), ['first', 'second', 'third']);
});

test('table starts in original order, toggles numeric sort and never mutates the snapshot', () => {
  const rows = ref([{ amount: 100 }, { amount: -20 }, { amount: 3 }]);
  const state = useTableSort(rows, columns);
  assert.equal(state.sorted.value, rows.value);
  assert.equal(state.ariaSort('amount'), 'none');
  state.toggleSort('amount');
  assert.deepEqual(state.sorted.value.map(row => row.amount), [-20, 3, 100]);
  assert.equal(state.ariaSort('amount'), 'ascending');
  state.toggleSort('amount');
  assert.deepEqual(state.sorted.value.map(row => row.amount), [100, 3, -20]);
  assert.equal(state.ariaSort('amount'), 'descending');
  assert.deepEqual(rows.value.map(row => row.amount), [100, -20, 3]);
});

test('unknown dates stay last in both directions and changing column resets direction', () => {
  const state = useTableSort(ref([{ date: null }, { date: 20 }, { date: 3 }]), columns);
  state.toggleSort('date');
  assert.deepEqual(state.sorted.value.map(row => row.date), [3, 20, null]);
  state.toggleSort('date');
  assert.deepEqual(state.sorted.value.map(row => row.date), [20, 3, null]);
  state.toggleSort('amount');
  assert.equal(state.descending.value, false);
  assert.equal(state.ariaSort('date'), 'none');
});

test('outpoints sort by full transaction ID then numeric output index', () => {
  const state = useTableSort(ref([{ txid: 'b', vout: 0 }, { txid: 'a', vout: 10 }, { txid: 'a', vout: 2 }]), columns);
  state.toggleSort('outpoint');
  assert.deepEqual(state.sorted.value.map(row => `${row.txid}:${row.vout}`), ['a:2', 'a:10', 'b:0']);
  state.toggleSort('outpoint');
  assert.deepEqual(state.sorted.value.map(row => `${row.txid}:${row.vout}`), ['b:0', 'a:10', 'a:2']);
});

test('live snapshots preserve the chosen sort and equal values keep stable order', () => {
  const rows = ref([{ id: 'a', amount: 5 }]);
  const state = useTableSort(rows, columns);
  state.toggleSort('amount');
  rows.value = [{ id: 'a', amount: 5 }, { id: 'b', amount: 5 }, { id: 'c', amount: -1 }];
  assert.deepEqual(state.sorted.value.map(row => row.id), ['c', 'a', 'b']);
  assert.equal(state.sortKey.value, 'amount');
  state.sortKey.value = '';
  assert.deepEqual(state.sorted.value.map(row => row.id), ['a', 'b', 'c']);
});