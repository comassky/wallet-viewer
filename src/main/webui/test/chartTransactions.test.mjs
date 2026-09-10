import assert from 'node:assert/strict';
import test from 'node:test';
import { groupChartTransactions } from '../src/utils/chartTransactions.ts';

const day = 86_400;
const point = time => ({ time, balanceSats: 100, valueEur: null, valueUsd: null });
const transaction = (txid, timestamp) => ({ txid, timestamp });

test('daily markers group transactions without adding points on inactive days', () => {
  const transactions = [transaction('older', day + 1), transaction('newer', day + 2)];
  assert.deepEqual(groupChartTransactions([point(day), point(day * 2)], transactions, time => time), [
    { time: day, transactions: [transactions[1], transactions[0]] },
  ]);
  assert.equal(transactions[0].txid, 'older');
});

test('weekly markers use the final plotted day and exclude dates outside the visible window', () => {
  const transactions = [transaction('outside', day), transaction('first', day * 2), transaction('last', day * 3)];
  assert.deepEqual(groupChartTransactions([point(day * 2), point(day * 3)], transactions, time => Math.floor(time / (7 * day))), [
    { time: day * 3, transactions: [transactions[2], transactions[1]] },
  ]);
});

test('monthly markers keep month and year boundaries separate', () => {
  const december = Date.UTC(2025, 11, 31) / 1000;
  const january = Date.UTC(2026, 0, 1) / 1000;
  const transactions = [transaction('dec', december), transaction('jan', january)];
  const groups = groupChartTransactions([point(december), point(january)], transactions, time => {
    const date = new Date(time * 1000);
    return date.getUTCFullYear() * 12 + date.getUTCMonth();
  });
  assert.deepEqual(groups.map(group => group.transactions.map(tx => tx.txid)), [['dec'], ['jan']]);
});

test('pending transactions map to today only if today is in the history', () => {
  const pending = transaction('pending', null);
  assert.deepEqual(groupChartTransactions([point(day * 2)], [pending], time => time, day * 2 + 15), [
    { time: day * 2, transactions: [pending] },
  ]);
  assert.deepEqual(groupChartTransactions([point(day)], [pending], time => time, day * 2 + 15), []);
});

test('empty or concealed history produces no transaction markers', () => {
  assert.deepEqual(groupChartTransactions([], [transaction('hidden', day)], time => time), []);
  assert.deepEqual(groupChartTransactions([point(day)], [], time => time), []);
});