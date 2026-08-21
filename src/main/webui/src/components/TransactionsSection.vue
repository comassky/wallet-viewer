<script setup lang="ts">
import { toRef } from 'vue';
import { currencyLabel, type Currency } from '../currency';
import type { Transaction } from '../types/wallet';
import { shortId, formatDate } from '../utils/format';
import { useExpandedTransaction } from '../composables/useExpandedTransaction';
import ConfirmationStatus from './ConfirmationStatus.vue';
import TransactionBadge from './TransactionBadge.vue';
import TransactionDetails from './TransactionDetails.vue';

const props = defineProps<{
  transactions: Transaction[];
  currency: Currency;
  amount: (sats: number, signed?: boolean) => string;
}>();
const { expandedTxid, details, loading, error, toggle, retry } = useExpandedTransaction(toRef(props, 'transactions'));
</script>

<template>
  <section>
    <div class="mb-4 flex flex-wrap items-center justify-between gap-2">
      <h2 class="sr-only">Activity</h2>
      <p class="text-xs text-slate-500">Select a transaction to expand or collapse its details</p>
    </div>
    <ul v-if="transactions.length" class="grid min-w-0 gap-3 lg:hidden" aria-label="Transactions">
      <li v-for="tx in transactions" :key="tx.txid" class="wallet-panel min-w-0">
        <button
          :id="`transaction-mobile-toggle-${tx.txid}`"
          type="button"
          class="block w-full rounded-2xl p-4 text-left transition hover:bg-slate-800/50"
          :class="{ 'bg-accent/5': expandedTxid === tx.txid }"
          :aria-expanded="expandedTxid === tx.txid"
          :aria-controls="`transaction-mobile-details-${tx.txid}`"
          :aria-label="`${expandedTxid === tx.txid ? 'Collapse' : 'Expand'} transaction ${tx.txid}`"
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
          <span class="break-all text-right font-mono text-sky-400">{{ shortId(tx.txid) }}</span>
          <span class="text-slate-400">Date</span>
          <span class="text-right text-slate-300">{{ formatDate(tx.timestamp) }}</span>
          <span class="text-slate-400">Confirmations</span>
          <span class="text-right"><ConfirmationStatus :confirmations="tx.confirmations" /></span>
        </span>
        <span class="mt-3 flex items-center justify-end gap-2 text-xs text-accent">
          {{ expandedTxid === tx.txid ? 'Hide details' : 'Show details' }}
          <span aria-hidden="true" class="inline-block transition-transform motion-reduce:transition-none" :class="{ 'rotate-180': expandedTxid === tx.txid }">⌄</span>
        </span>
        </button>
        <div :id="`transaction-mobile-details-${tx.txid}`" :hidden="expandedTxid !== tx.txid" role="region" :aria-labelledby="`transaction-mobile-toggle-${tx.txid}`" class="border-t border-slate-700/50 bg-slate-950/30">
          <TransactionDetails v-if="expandedTxid === tx.txid" :id-prefix="`transaction-mobile-${tx.txid}`" :transaction="tx" :details="details" :loading="loading" :error="error" :currency="currency" :amount="amount" @retry="retry" />
        </div>
      </li>
    </ul>
    <div v-if="transactions.length" class="wallet-panel hidden lg:block">
      <table class="w-full table-fixed text-sm">
        <thead>
          <tr class="text-xs uppercase text-slate-400">
            <th class="px-3 py-3 text-left font-medium">Type</th>
            <th class="px-3 py-3 text-left font-medium">Transaction</th>
            <th class="px-3 py-3 text-left font-medium">Date</th>
            <th class="px-3 py-3 text-right font-medium">Amount ({{ currencyLabel(currency) }})</th>
            <th class="px-3 py-3 text-right font-medium">Confirmations</th>
            <th class="w-24 px-3 py-3"><span class="sr-only">Details</span></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="tx in transactions" :key="tx.txid">
          <tr class="cursor-pointer border-t border-slate-800 transition hover:bg-slate-800/50" :class="{ 'bg-accent/5': expandedTxid === tx.txid }" @click="toggle(tx.txid)">
            <td class="px-3 py-2.5"><TransactionBadge :type="tx.type" /></td>
            <td class="px-3 py-2.5">
              <span class="font-mono text-sky-400">{{ shortId(tx.txid) }}</span>
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