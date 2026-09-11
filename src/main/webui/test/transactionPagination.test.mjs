import assert from 'node:assert/strict';
import test from 'node:test';
import { TRANSACTION_PAGE_SIZE, paginateItems, paginationRange } from '@/utils/transactionGraph.ts';

test('default size, zero-based page state and one-based display counters', () => {
  assert.equal(TRANSACTION_PAGE_SIZE, 5);
  assert.deepEqual(paginationRange(12, 1), {
    total: 12, page: 1, pageCount: 3, pageSize: 5, start: 5, end: 10, remaining: 7,
  });
  const last = paginationRange(12, 2);
  assert.equal(last.start + 1, 11);
  assert.equal(last.end, 12);
  assert.equal(last.page + 1, last.pageCount);
});

test('empty, exact page boundaries, short last pages and custom page sizes', () => {
  assert.deepEqual(paginateItems([], 100), {
    total: 0, page: 0, pageCount: 1, pageSize: 5, start: 0, end: 0, remaining: 0, entries: [],
  });
  for (const [total, pageCount, lastSize] of [[1, 1, 1], [5, 1, 5], [6, 2, 1], [10, 2, 5], [11, 3, 1]]) {
    const range = paginationRange(total, 999);
    assert.equal(range.pageCount, pageCount);
    assert.equal(range.page, pageCount - 1);
    assert.equal(range.end - range.start, lastSize);
  }
  assert.equal(paginationRange(10, 0, 3).pageCount, 4);
  assert.equal(paginationRange(10, 3, 3).start, 9);
});

test('pages clamp after collection shrink, stay valid on growth and can reset to the first page', () => {
  let page = paginationRange(103, 20).page;
  page = paginationRange(7, page).page;
  assert.equal(page, 1);
  assert.equal(paginationRange(200, page).page, 1);
  assert.equal(paginationRange(200, 0).start, 0);
  assert.equal(paginationRange(0, page).page, 0);
  assert.equal(paginationRange(7, -1).page, 0);
  assert.equal(paginationRange(7, 999).page, 1);
});

test('non-finite or fractional pagination arguments cannot cause invalid ranges', () => {
  for (const invalid of [NaN, Infinity, -Infinity]) {
    assert.equal(paginationRange(10, invalid).page, 0);
    assert.equal(paginationRange(invalid).total, 0);
    assert.equal(paginationRange(10, 0, invalid).pageSize, 1);
  }
  assert.equal(paginationRange(-5).total, 0);
  assert.equal(paginationRange(10.9, 1.9).page, 1);
  assert.equal(paginationRange(10, 0, 0).pageSize, 1);
  assert.equal(paginationRange(10, 0, -3).pageSize, 1);
});

test('global input indices and protocol output indices/order survive paging, without sorting or deduplication', () => {
  const protocolIndices = [9, 0, 4, 3, 8, 2, 7, 1, 6, 5, 10];
  const items = Object.freeze(protocolIndices.map(index => Object.freeze({ index, address: 'repeated', value: index })));
  const recovered = [];
  for (let page = 0; page < 3; page++) {
    const result = paginateItems(items, page);
    for (const entry of result.entries) {
      assert.equal(entry.item, items[entry.index]);
      recovered.push(entry.item.index);
    }
  }
  assert.deepEqual(recovered, protocolIndices);
  assert.deepEqual(paginateItems(items, 1).entries.map(entry => entry.index), [5, 6, 7, 8, 9]);
  assert.deepEqual(paginateItems(items, 2).entries.map(entry => entry.index), [10]);
});

test('large list slices stay bounded and every element remains reachable by page', () => {
  const items = Array.from({ length: 100_003 }, (_, index) => index);
  let visited = 0;
  const { pageCount } = paginationRange(items.length);
  for (let page = 0; page < pageCount; page++) {
    const result = paginateItems(items, page);
    assert.ok(result.entries.length <= TRANSACTION_PAGE_SIZE);
    for (const entry of result.entries) {
      assert.equal(entry.index, visited);
      assert.equal(entry.item, visited++);
    }
  }
  assert.equal(visited, items.length);
});