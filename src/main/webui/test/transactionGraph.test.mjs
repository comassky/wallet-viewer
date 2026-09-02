import assert from 'node:assert/strict';
import test from 'node:test';
import {
  TRANSACTION_PAGE_SIZE, summarizeValues, transactionGraphLayout, transactionGraphPage,
} from '../src/utils/transactionGraph.ts';

test('small transactions are complete and every branch connects only to the transaction', () => {
  const graph = transactionGraphLayout(3, 2);
  assert.equal(graph.inputs.length, 3);
  assert.equal(graph.outputs.length, 2);
  for (const node of graph.inputs) {
    assert.ok(node.path.startsWith(`M 400 ${node.y}`));
    assert.ok(node.path.endsWith(`440 ${graph.centerY}`));
  }
  for (const node of graph.outputs) {
    assert.ok(node.path.startsWith(`M 560 ${graph.centerY}`));
    assert.ok(node.path.endsWith(`600 ${node.y}`));
  }
  for (let count = 0; count <= TRANSACTION_PAGE_SIZE; count++) {
    const items = Array.from({ length: count }, (_, index) => ({ value: index }));
    const page = transactionGraphPage(items);
    assert.equal(page.group, null);
    assert.equal(page.entries.length, count);
    assert.equal(transactionGraphLayout(count, count).inputs.length, count);
  }
});

test('compact cards and canvas avoid oversized rows for small and fully populated pages', () => {
  const small = transactionGraphLayout(1, 1);
  assert.equal(small.nodeHeight, 76);
  assert.equal(small.height, 224);
  assert.equal(small.inputs[0].y, small.centerY);
  assert.equal(small.outputs[0].y, small.centerY);
  const full = transactionGraphLayout(50, 50);
  assert.equal(full.height, 632);
  assert.equal(full.inputs[1].y - full.inputs[0].y, full.nodeHeight + 10);
});

test('asymmetric and huge layouts remain bounded, inside the canvas and without card overlap', () => {
  const maxHeight = transactionGraphLayout(6, 6).height;
  for (const [inputs, outputs, inputPage, outputPage] of [
    [1, 1, 0, 0], [1, 50, 0, 9], [200, 2, 39, 0], [0, 0, 0, 0],
    [6, 11, 1, 2], [100_000, 1, 1234, 0], [1_000_000_000, 1_000_000_000, 99999, 88888],
    [6, 6, -2, 100],
  ]) {
    const graph = transactionGraphLayout(inputs, outputs, inputPage, outputPage);
    assert.ok(graph.height <= maxHeight);
    assert.ok(graph.centerY > graph.headerHeight && graph.centerY < graph.height);
    assert.equal(graph.width, 1000);
    for (const nodes of [graph.inputs, graph.outputs]) {
      assert.ok(nodes.length <= TRANSACTION_PAGE_SIZE + 1);
      nodes.forEach((node, index) => {
        assert.ok(node.y - graph.nodeHeight / 2 >= graph.headerHeight);
        assert.ok(node.y + graph.nodeHeight / 2 <= graph.height);
        assert.ok(Number.isFinite(node.y));
        if (index) assert.ok(node.y - nodes[index - 1].y > graph.nodeHeight);
      });
    }
  }
});

test('layout node counts match each current page plus its group, including a short last page', () => {
  const items = Array.from({ length: 11 }, (_, index) => ({ value: index + 1 }));
  for (const requestedPage of [-1, 0, 1, 2, 999]) {
    const page = transactionGraphPage(items, requestedPage);
    const graph = transactionGraphLayout(11, 11, requestedPage, requestedPage);
    assert.equal(graph.inputs.length, page.entries.length + (page.group ? 1 : 0));
    assert.equal(graph.outputs.length, graph.inputs.length);
  }
  assert.equal(transactionGraphLayout(11, 11, 2, 2).inputs.length, 2);
});

test('remaining group totals include every off-page value, including previous pages', () => {
  const items = Array.from({ length: 11 }, (_, index) => ({ value: index + 1 }));
  const summary = summarizeValues(items);
  assert.deepEqual(summary, { total: 66, knownTotal: 66, unknownCount: 0 });
  const expected = [
    { count: 6, total: 51, knownTotal: 51, unknownCount: 0, nextPage: 1 },
    { count: 6, total: 26, knownTotal: 26, unknownCount: 0, nextPage: 2 },
    { count: 10, total: 55, knownTotal: 55, unknownCount: 0, nextPage: 0 },
  ];
  expected.forEach((group, index) => {
    const page = transactionGraphPage(items, index, summary);
    assert.deepEqual(page.group, group);
    assert.equal(summarizeValues(page.entries.map(entry => entry.item)).total + page.group.total, summary.total);
    assert.equal(page.entries.length + page.group.count, items.length);
  });
});

test('exploring the group cycles through all elements without accumulating rendered nodes', () => {
  const items = Array.from({ length: 103 }, (_, index) => ({ value: index, address: 'same-address' }));
  const summary = summarizeValues(items);
  let requestedPage = 0;
  const seen = [];
  for (let step = 0; step < Math.ceil(items.length / TRANSACTION_PAGE_SIZE); step++) {
    const page = transactionGraphPage(items, requestedPage, summary);
    assert.ok(page.entries.length <= TRANSACTION_PAGE_SIZE);
    seen.push(...page.entries.map(entry => entry.index));
    requestedPage = page.group.nextPage;
  }
  assert.deepEqual(seen, items.map((_, index) => index));
  assert.equal(requestedPage, 0);
  assert.deepEqual(transactionGraphPage(items, requestedPage, summary).entries.map(entry => entry.index), [0, 1, 2, 3, 4]);
});

test('null and coinbase values remain explicit, never silently contributing zero to a total', () => {
  const coinbase = [{ value: null, coinbase: true }];
  assert.deepEqual(summarizeValues(coinbase), { total: null, knownTotal: 0, unknownCount: 1 });
  assert.equal(transactionGraphPage(coinbase).entries[0].item.value, null);
  assert.equal(transactionGraphPage(coinbase).group, null);

  const items = [...Array.from({ length: 5 }, () => ({ value: 10 })), { value: null }, { value: 0 }];
  assert.deepEqual(transactionGraphPage(items).group, {
    count: 2, total: null, knownTotal: 0, unknownCount: 1, nextPage: 1,
  });
  assert.deepEqual(transactionGraphPage(items, 1).group, {
    count: 5, total: 50, knownTotal: 50, unknownCount: 0, nextPage: 0,
  });
  assert.deepEqual(summarizeValues([]), { total: 0, knownTotal: 0, unknownCount: 0 });
  assert.deepEqual(summarizeValues([{ value: 0 }]), { total: 0, knownTotal: 0, unknownCount: 0 });
});

test('integer satoshi sums remain exact near the total bitcoin supply', () => {
  const items = [{ value: 2_099_999_999_999_990 }, ...Array.from({ length: 10 }, () => ({ value: 1 }))];
  const summary = summarizeValues(items);
  assert.equal(summary.total, 2_100_000_000_000_000);
  for (let index = 0; index < 3; index++) {
    const page = transactionGraphPage(items, index, summary);
    const visible = summarizeValues(page.entries.map(entry => entry.item));
    assert.equal(visible.total + page.group.total, summary.total);
    assert.ok(Number.isSafeInteger(page.group.total));
  }
});

test('a cached summary makes page changes visit only the visible values in a large transaction', () => {
  const items = Array.from({ length: 100_003 }, (_, index) => ({ value: index % 100, index }));
  const summary = summarizeValues(items);
  let reads = 0;
  const tracked = new Proxy(items, {
    get(target, key, receiver) {
      if (typeof key === 'string' && /^\d+$/.test(key)) reads++;
      return Reflect.get(target, key, receiver);
    },
  });
  const page = transactionGraphPage(tracked, 20_000, summary);
  assert.equal(reads, 3);
  assert.deepEqual(page.entries.map(entry => entry.index), [100_000, 100_001, 100_002]);
  assert.equal(page.group.count, 100_000);
  assert.equal(page.group.total + summarizeValues(page.entries.map(entry => entry.item)).total, summary.total);
  assert.equal(page.group.nextPage, 0);
});