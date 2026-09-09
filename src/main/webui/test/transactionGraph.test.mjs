import assert from 'node:assert/strict';
import test from 'node:test';
import { transactionGraphLayout } from '../src/utils/transactionGraph.ts';

test('every input and output gets a node and connects through the transaction', () => {
  const graph = transactionGraphLayout(3, 2);
  assert.equal(graph.inputs.length, 3);
  assert.equal(graph.outputs.length, 2);
  for (const node of graph.inputs) assert.ok(node.path.endsWith(`420 ${graph.centerY}`));
  for (const node of graph.outputs) assert.ok(node.path.startsWith(`M 540 ${graph.centerY}`));
});

test('asymmetric and large transactions remain inside the scrollable graph without overlap', () => {
  for (const [inputs, outputs] of [[1, 1], [1, 50], [200, 2], [0, 0]]) {
    const graph = transactionGraphLayout(inputs, outputs);
    for (const nodes of [graph.inputs, graph.outputs]) {
      nodes.forEach((node, index) => {
        assert.ok(node.y >= 28 && node.y <= graph.height - 28);
        if (index) assert.ok(node.y - nodes[index - 1].y >= 56);
      });
    }
  }
});