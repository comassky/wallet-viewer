import type { BalancePoint, Transaction } from '../types/wallet.ts';

export interface ChartTransactionGroup {
  time: number;
  transactions: Transaction[];
}

export function groupChartTransactions(
  points: readonly BalancePoint[],
  transactions: readonly Transaction[],
  bucketId: (day: number) => number,
  nowSeconds = Date.now() / 1000,
): ChartTransactionGroup[] {
  const daySeconds = 86_400;
  const visibleDays = new Set(points.map(point => point.time));
  const bucketTimes = new Map(points.map(point => [bucketId(point.time), point.time]));
  const datedTransactions = transactions.flatMap(transaction => {
    const day = Math.floor((transaction.timestamp ?? nowSeconds) / daySeconds) * daySeconds;
    if (!visibleDays.has(day)) return [];
    const time = bucketTimes.get(bucketId(day));
    return time === undefined ? [] : [{ time, transaction }];
  });
  const groups = Map.groupBy(datedTransactions, item => item.time);
  return Array.from(groups, ([time, group]) => ({
    time,
    transactions: group.map(item => item.transaction).toSorted((left, right) => (right.timestamp ?? nowSeconds) - (left.timestamp ?? nowSeconds)
      || left.txid.localeCompare(right.txid)),
  })).toSorted((left, right) => left.time - right.time);
}