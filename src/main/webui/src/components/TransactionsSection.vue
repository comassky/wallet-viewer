<script setup lang="ts">
import { ref, toRef, watch } from 'vue';
import { currencyLabel, type Currency } from '../currency';
import type { Transaction } from '../types/wallet';
import { shortId, formatDate, transactionLabels } from '../utils/format';
import { useExpandedTransaction } from '../composables/useExpandedTransaction';
import { useTableSort, type SortColumn } from '../composables/useTableSort';
import CopyValue from './CopyValue.vue';
import ConfirmationStatus from './ConfirmationStatus.vue';
import TransactionBadge from './TransactionBadge.vue';
import TransactionDetails from './TransactionDetails.vue';

const props = defineProps<{
  transactions: Transaction[];
  currency: Currency;
  amount: (sats: number, signed?: boolean) => string;
}>();
const { expandedTxid, details, loading, error, toggle, retry } = useExpandedTransaction(toRef(props, 'transactions'));

// Briefly highlight transactions that arrive after the first populated snapshot.
const flashing = ref(new Set<string>());
let known: Set<string> | null = null;
watch(() => props.transactions, (txs) => {
  const ids = new Set(txs.map(tx => tx.txid));
  if (known === null) {
    if (ids.size === 0) return;
    known = ids;
    return;
  }
  for (const id of ids) {
    if (!known.has(id)) {
      flashing.value.add(id);
      window.setTimeout(() => flashing.value.delete(id), 2400);
    }
  }
  known = ids;
}, { immediate: true });

const columns: SortColumn<Transaction>[] = [
  { key: 'type', label: 'Type', value: tx => transactionLabels[tx.type] },
  { key: 'txid', label: 'Transaction', value: tx => tx.txid },
  { key: 'timestamp', label: 'Date', value: tx => tx.timestamp },
  { key: 'amount', label: 'Amount', value: tx => tx.amount, numeric: true },
  { key: 'confirmations', label: 'Confirmations', value: tx => tx.confirmations, numeric: true },
];
const { sorted, sortKey, descending, toggleSort, ariaSort } = useTableSort(toRef(props, 'transactions'), columns);
</script>

<template>
  <section>
    <div class="mb-4 flex flex-wrap items-center justify-between gap-2">
      <h2 class="sr-only">Activity</h2>
      <p class="text-xs text-slate-500">Select a row for details · click an address or transaction ID to copy</p>
      <div class="flex items-center gap-2 text-xs lg:hidden">
        <label>Sort by
          <select v-model="sortKey" class="ml-2 min-h-11 rounded-lg border border-slate-700 bg-slate-900 px-2">
            <option value="">Default order</option>
            <option v-for="column in columns" :key="column.key" :value="column.key">{{ column.label }}</option>
          </select>
        </label>
        <button v-if="sortKey" type="button" class="button-secondary rounded-lg px-3" :aria-label="descending ? 'Sort ascending' : 'Sort descending'" @click="descending = !descending">{{ descending ? '↓' : '↑' }}</button>
      </div>
    </div>
    <ul v-if="transactions.length" class="grid min-w-0 gap-3 lg:hidden" aria-label="Transactions">
      <li v-for="tx in sorted" :key="tx.txid" class="wallet-panel min-w-0">
        <div
          class="block w-full cursor-pointer rounded-2xl p-4 text-left transition hover:bg-slate-800/50"
          :class="{ 'bg-accent/5': expandedTxid === tx.txid, 'row-flash': flashing.has(tx.txid) }"
          @click="toggle(tx.txid)"
        >
        <span class="mb-3 flex flex-wrap items-center justify-between gap-2">
          <TransactionBadge :type="tx.type" />
          <span class="text-sm font-semibold tabular-nums" :class="tx.amount >= 0 ? 'text-emerald-400' : 'text-rose-400'">
            {{ amount(tx.amount, true) }} {{ currencyLabel(currency) }}
          </span>
        </span>
        <span class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-2 text-sm">
          <span class="text-slate-400">Transaction</span>
          <span class="text-right"><CopyValue :value="tx.txid" :display="shortId(tx.txid)" label="transaction ID" /></span>
          <span class="text-slate-400">Date</span>
          <span class="text-right text-slate-300">{{ formatDate(tx.timestamp) }}</span>
          <span class="text-slate-400">Confirmations</span>
          <span class="text-right"><ConfirmationStatus :confirmations="tx.confirmations" /></span>
        </span>
        <button :id="`transaction-mobile-toggle-${tx.txid}`" type="button" :aria-expanded="expandedTxid === tx.txid" :aria-controls="`transaction-mobile-details-${tx.txid}`" :aria-label="`${expandedTxid === tx.txid ? 'Collapse' : 'Expand'} transaction ${tx.txid}`" class="mt-3 flex w-full items-center justify-end gap-2 rounded-lg text-xs text-accent" @click.stop="toggle(tx.txid)">
          {{ expandedTxid === tx.txid ? 'Hide details' : 'Show details' }}
          <span aria-hidden="true" class="inline-block transition-transform motion-reduce:transition-none" :class="{ 'rotate-180': expandedTxid === tx.txid }">⌄</span>
        </button>
        </div>
        <div :id="`transaction-mobile-details-${tx.txid}`" :hidden="expandedTxid !== tx.txid" role="region" :aria-labelledby="`transaction-mobile-toggle-${tx.txid}`" class="border-t border-slate-700/50 bg-slate-950/30">
          <TransactionDetails v-if="expandedTxid === tx.txid" :id-prefix="`transaction-mobile-${tx.txid}`" :transaction="tx" :details="details" :loading="loading" :error="error" :currency="currency" :amount="amount" @retry="retry" />
        </div>
      </li>
    </ul>
    <div v-if="transactions.length" class="wallet-panel hidden lg:block">
      <table class="w-full table-fixed text-sm">
        <thead>
          <tr class="text-xs uppercase text-slate-400">
            <th v-for="column in columns" :key="column.key" scope="col" :aria-sort="ariaSort(column.key)" class="px-3 py-1 font-medium" :class="column.numeric ? 'text-right' : 'text-left'">
              <button type="button" class="w-full rounded-sm py-2 text-inherit transition hover:text-accent" :class="column.numeric ? 'text-right' : 'text-left'" @click="toggleSort(column.key)">
                {{ column.label }}{{ column.key === 'amount' ? ` (${currencyLabel(currency)})` : '' }}
                <span aria-hidden="true" class="ml-1" :class="sortKey === column.key ? 'text-accent' : 'text-slate-600'">{{ sortKey === column.key ? (descending ? '↓' : '↑') : '↕' }}</span>
              </button>
            </th>
            <th class="w-24 px-3 py-3"><span class="sr-only">Details</span></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="tx in sorted" :key="tx.txid">
          <tr class="cursor-pointer border-t border-slate-800 transition hover:bg-slate-800/50" :class="{ 'bg-accent/5': expandedTxid === tx.txid, 'row-flash': flashing.has(tx.txid) }" @click="toggle(tx.txid)">
            <td class="px-3 py-2.5"><TransactionBadge :type="tx.type" /></td>
            <td class="px-3 py-2.5">
              <CopyValue :value="tx.txid" :display="shortId(tx.txid)" label="transaction ID" />
            </td>
            <td class="px-3 py-2.5 text-slate-300">{{ formatDate(tx.timestamp) }}</td>
            <td class="px-3 py-2.5 text-right tabular-nums" :class="tx.amount >= 0 ? 'text-emerald-400' : 'text-rose-400'">
              {{ amount(tx.amount, true) }}
            </td>
            <td class="px-3 py-2.5 text-right"><ConfirmationStatus :confirmations="tx.confirmations" compact /></td>
            <td class="px-3 py-2.5 text-right">
              <button :id="`transaction-desktop-toggle-${tx.txid}`" type="button" @click.stop="toggle(tx.txid)" :aria-expanded="expandedTxid === tx.txid" :aria-controls="`transaction-desktop-details-${tx.txid}`" :aria-label="`${expandedTxid === tx.txid ? 'Collapse' : 'Expand'} transaction ${tx.txid}`" class="inline-flex items-center gap-2 rounded-lg px-2 text-xs text-accent transition hover:bg-accent/10">
                {{ expandedTxid === tx.txid ? 'Hide' : 'Details' }}
                <span aria-hidden="true" class="inline-block transition-transform motion-reduce:transition-none" :class="{ 'rotate-180': expandedTxid === tx.txid }">⌄</span>
              </button>
            </td>
          </tr>
          <tr :hidden="expandedTxid !== tx.txid">
            <td colspan="6" class="border-t border-slate-700/50 bg-slate-950/30 p-0">
              <div :id="`transaction-desktop-details-${tx.txid}`" role="region" :aria-labelledby="`transaction-desktop-toggle-${tx.txid}`">
                <TransactionDetails v-if="expandedTxid === tx.txid" :id-prefix="`transaction-desktop-${tx.txid}`" :transaction="tx" :details="details" :loading="loading" :error="error" :currency="currency" :amount="amount" @retry="retry" />
              </div>
            </td>
          </tr>
          </template>
        </tbody>
      </table>
    </div>
    <div v-else class="wallet-panel p-10 text-center"><p class="text-sm text-slate-300">No activity yet</p><p class="mt-2 text-xs text-slate-500">Transactions will appear here as your wallet synchronizes.</p></div>
  </section>
</template>