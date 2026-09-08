import { computed, ref, watch, type Ref } from 'vue';
import type { Transaction } from '../types/wallet';
import { useTableSort, type SortColumn } from './useTableSort.ts';

export const transactionFilters = [
  { id: 'all', label: 'All' },
  { id: 'received', label: 'Received' },
  { id: 'sent', label: 'Sent' },
  { id: 'pending', label: 'Pending' },
] as const;
export type TransactionFilter = typeof transactionFilters[number]['id'];
export const transactionPageSize = 25;

function matches(tx: Transaction, filter: TransactionFilter): boolean {
  return filter === 'all' || (filter === 'pending' ? tx.confirmations === 0 : tx.type === filter);
}

/** Filter and sort the cache locally; live updates never reset the user's browsing choices. */
export function useTransactionList(transactions: Ref<Transaction[]>, columns: SortColumn<Transaction>[]) {
  const query = ref('');
  const filter = ref<TransactionFilter>('all');
  const limit = ref(transactionPageSize);
  const normalizedQuery = computed(() => query.value.trim().toLowerCase());
  const searched = computed(() => transactions.value.filter(tx =>
    tx.txid.toLowerCase().includes(normalizedQuery.value)
    || (tx.addresses ?? []).some(address => address.toLowerCase().includes(normalizedQuery.value))));
  const counts = computed(() => Object.fromEntries(transactionFilters.map(item => [item.id, searched.value.filter(tx => matches(tx, item.id)).length])) as Record<TransactionFilter, number>);
  const filtered = computed(() => searched.value.filter(tx => matches(tx, filter.value)));
  const sort = useTableSort(filtered, columns);
  const visible = computed(() => sort.sorted.value.slice(0, limit.value));
  const hasMore = computed(() => visible.value.length < filtered.value.length);
  watch([normalizedQuery, filter, sort.sortKey, sort.descending], () => { limit.value = transactionPageSize; }, { flush: 'sync' });

  function showMore(): void { limit.value += transactionPageSize; }
  function resetFilters(): void { query.value = ''; filter.value = 'all'; }
  return { ...sort, query, filter, counts, filtered, visible, hasMore, showMore, resetFilters };
}