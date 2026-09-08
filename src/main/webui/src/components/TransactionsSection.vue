<script setup lang="ts">
import { computed, toRef } from 'vue';
import { currencyLabel, formatAmount, type Currency, type FiatCurrency } from '../currency';
import type { PriceRates, Transaction } from '../types/wallet';
import { shortId, formatDate, transactionLabels } from '../utils/format';
import { useExpandedTransaction } from '../composables/useExpandedTransaction';
import type { SortColumn } from '../composables/useTableSort';
import { transactionFilters, transactionPageSize, useTransactionList } from '../composables/useTransactionList';
import CopyValue from './CopyValue.vue';
import ConfirmationStatus from './ConfirmationStatus.vue';
import TransactionBadge from './TransactionBadge.vue';
import TransactionDetailsDialog from './TransactionDetailsDialog.vue';
import UiIcon from './UiIcon.vue';

const props = defineProps<{
  transactions: Transaction[];
  currency: Currency;
  fiatCurrency: FiatCurrency;
  rates: PriceRates | null;
  amount: (sats: number, signed?: boolean) => string;
}>();
const { expandedTxid, details, loading, error, toggle, retry } = useExpandedTransaction(toRef(props, 'transactions'));
// The dialog shows whichever transaction the shared selection currently points at.
const selectedTransaction = computed(() => props.transactions.find(tx => tx.txid === expandedTxid.value) ?? null);
function closeDialog(): void {
  if (expandedTxid.value) toggle(expandedTxid.value);
}

function openDetails(txid: string, event: MouseEvent): void {
  const target = event.currentTarget as HTMLElement;
  const trigger = target instanceof HTMLButtonElement ? target : target.querySelector<HTMLButtonElement>('[data-tx-details]');
  // The native dialog will restore focus to this action on close, including row clicks.
  trigger?.focus({ preventScroll: true });
  toggle(txid);
}

const columns: SortColumn<Transaction>[] = [
  { key: 'type', label: 'Type', value: tx => transactionLabels[tx.type] },
  { key: 'txid', label: 'Transaction', value: tx => tx.txid },
  { key: 'timestamp', label: 'Date', value: tx => tx.timestamp },
  { key: 'amount', label: 'Amount', value: tx => tx.amount, numeric: true },
  { key: 'confirmations', label: 'Confirmations', value: tx => tx.confirmations, numeric: true },
];
const { query, filter, counts, filtered, visible, hasMore, showMore, resetFilters, sortKey, descending, toggleSort, ariaSort } = useTransactionList(toRef(props, 'transactions'), columns);
</script>

<template>
  <section>
    <div v-if="transactions.length" class="mb-4 flex flex-col gap-3 xl:flex-row xl:items-end xl:justify-between">
      <div role="group" aria-label="Filter transactions" class="flex flex-wrap gap-2">
        <button v-for="item in transactionFilters" :key="item.id" type="button" :aria-pressed="filter === item.id" class="inline-flex min-h-11 items-center gap-2 rounded-xl border px-3 py-2 text-sm transition" :class="filter === item.id ? 'border-accent/60 bg-accent/10 text-accent' : 'border-slate-700 bg-slate-900 text-slate-300 hover:border-slate-500'" @click="filter = item.id">
          {{ item.label }}<span class="rounded-full bg-slate-800 px-2 py-0.5 text-xs tabular-nums text-slate-300">{{ counts[item.id] }}</span>
        </button>
      </div>
      <div class="w-full xl:max-w-sm">
        <label for="transaction-search" class="mb-1.5 block text-xs font-medium text-slate-300">Search transaction ID</label>
        <div class="flex items-center gap-2">
          <input id="transaction-search" v-model="query" type="search" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="Full or partial transaction ID" class="min-h-11 min-w-0 flex-1 rounded-xl border border-slate-700 bg-slate-900 px-3 py-2 text-sm placeholder:text-slate-500" />
          <button v-if="query || filter !== 'all'" type="button" class="button-secondary inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-xl px-4 text-sm font-medium" @click="resetFilters"><UiIcon name="close" />Reset</button>
        </div>
      </div>
    </div>
    <div class="mb-4 flex flex-wrap items-center justify-between gap-2">
      <h2 class="sr-only">Activity</h2>
      <div class="space-y-1 text-xs text-slate-400">
        <p v-if="transactions.length" role="status" aria-atomic="true">Showing {{ visible.length }} of {{ filtered.length }} transactions<span v-if="query || filter !== 'all'"> · {{ transactions.length }} total</span></p>
        <p>Fiat estimates use the current price, not the price at the time of each transaction.</p>
      </div>
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
    <!-- v-auto-animate fades new rows in and smoothly pushes the rows below down; honors prefers-reduced-motion. -->
    <ul v-if="filtered.length" v-auto-animate class="grid min-w-0 gap-3 lg:hidden" aria-label="Transactions">
      <li v-for="tx in visible" :key="tx.txid" class="wallet-panel min-w-0">
        <div
          class="block w-full cursor-pointer rounded-2xl p-4 text-left transition hover:bg-slate-800/50"
          @click="openDetails(tx.txid, $event)"
        >
        <span class="mb-3 flex flex-wrap items-center justify-between gap-2">
          <TransactionBadge :type="tx.type" />
          <span class="text-right text-sm font-semibold tabular-nums">
            <span :class="tx.amount >= 0 ? 'text-emerald-400' : 'text-rose-400'">{{ amount(tx.amount, true) }} {{ currencyLabel(currency) }}</span>
            <span class="mt-1 block text-xs font-normal text-slate-400">{{ rates ? '≈ ' : '' }}{{ formatAmount(tx.amount, fiatCurrency, rates, true) }} {{ fiatCurrency }}</span>
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
        <button type="button" data-tx-details aria-haspopup="dialog" aria-controls="transaction-details-dialog" :aria-label="`Show details for transaction ${tx.txid}`" class="mt-3 flex w-full items-center justify-end gap-2 rounded-lg text-xs text-accent" @click.stop="openDetails(tx.txid, $event)">
          Show details
          <UiIcon name="arrow-right" />
        </button>
        </div>
      </li>
    </ul>
    <div v-if="filtered.length" class="wallet-panel hidden lg:block">
      <table class="w-full table-fixed text-sm">
        <thead>
          <tr class="text-xs uppercase text-slate-400">
            <th v-for="column in columns" :key="column.key" scope="col" :aria-sort="ariaSort(column.key)" class="px-3 py-1 font-medium" :class="column.numeric ? 'text-right' : 'text-left'">
              <button type="button" class="w-full rounded-sm py-2 text-inherit transition hover:text-accent" :class="column.numeric ? 'text-right' : 'text-left'" @click="toggleSort(column.key)">
                {{ column.label }}{{ column.key === 'amount' ? ` (${currencyLabel(currency)})` : '' }}
                <span aria-hidden="true" class="ml-1" :class="sortKey === column.key ? 'text-accent' : 'text-slate-600'">{{ sortKey === column.key ? (descending ? '↓' : '↑') : '↕' }}</span>
              </button>
            </th>
            <th scope="col" class="w-24 px-3 py-1 text-right font-medium"><span class="sr-only">Details</span></th>
          </tr>
        </thead>
        <tbody v-auto-animate>
          <tr v-for="tx in visible" :key="tx.txid" class="cursor-pointer border-t border-slate-800 transition hover:bg-slate-800/50 focus-within:bg-slate-800/50" @click="openDetails(tx.txid, $event)">
            <td class="px-3 py-2.5"><TransactionBadge :type="tx.type" /></td>
            <td class="px-3 py-2.5">
              <CopyValue :value="tx.txid" :display="shortId(tx.txid)" label="transaction ID" />
            </td>
            <td class="px-3 py-2.5 text-slate-300">{{ formatDate(tx.timestamp) }}</td>
            <td class="px-3 py-2.5 text-right tabular-nums">
              <span :class="tx.amount >= 0 ? 'text-emerald-400' : 'text-rose-400'">{{ amount(tx.amount, true) }}</span>
              <span class="mt-1 block text-xs text-slate-400">{{ rates ? '≈ ' : '' }}{{ formatAmount(tx.amount, fiatCurrency, rates, true) }} {{ fiatCurrency }}</span>
            </td>
            <td class="px-3 py-2.5 text-right"><ConfirmationStatus :confirmations="tx.confirmations" compact /></td>
            <td class="px-3 py-2.5 text-right"><button type="button" data-tx-details aria-haspopup="dialog" aria-controls="transaction-details-dialog" :aria-label="`Show details for transaction ${tx.txid}`" class="inline-flex items-center justify-center rounded-lg px-2 py-1 text-accent hover:bg-accent/10" @click.stop="openDetails(tx.txid, $event)"><UiIcon name="chevron-right" /></button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else-if="transactions.length" class="wallet-panel p-10 text-center">
      <UiIcon name="search" class="mx-auto mb-3 h-8 w-8 text-slate-400" />
      <p class="text-sm text-slate-200">No matching transactions</p>
      <p class="mt-2 text-xs text-slate-400">Try another transaction ID or select a different filter.</p>
      <button type="button" class="button-secondary mt-4 rounded-lg px-4 text-sm" @click="resetFilters">Reset filters</button>
    </div>
    <div v-else class="wallet-panel p-10 text-center">
      <UiIcon name="inbox" class="mx-auto mb-3 h-8 w-8 text-slate-600" />
      <p class="text-sm text-slate-300">No activity yet</p>
      <p class="mt-2 text-xs text-slate-500">Transactions will appear here as your wallet synchronizes.</p>
    </div>
    <div v-if="filtered.length > transactionPageSize" class="mt-5 text-center">
      <button type="button" :disabled="!hasMore" class="button-secondary rounded-xl px-6 py-2 text-sm font-semibold disabled:cursor-default disabled:opacity-60" @click="showMore">{{ hasMore ? 'Show more transactions' : 'All matching transactions shown' }}</button>
    </div>
    <TransactionDetailsDialog :transaction="selectedTransaction" :details="details" :loading="loading" :error="error" :currency="currency" :amount="amount" @close="closeDialog" @retry="retry" />
  </section>
</template>