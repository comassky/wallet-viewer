<script setup lang="ts">
import { currencyLabel, type Currency } from '../currency';
import type { Transaction } from '../types/wallet';
import { shortId, formatDate } from '../utils/format';
import ConfirmationStatus from './ConfirmationStatus.vue';
import TransactionBadge from './TransactionBadge.vue';

defineProps<{
  transactions: Transaction[];
  currency: Currency;
  amount: (sats: number, signed?: boolean) => string;
}>();
const emit = defineEmits<{ inspect: [transaction: Transaction, trigger: HTMLButtonElement] }>();

function inspect(transaction: Transaction, event: MouseEvent): void {
  emit('inspect', transaction, event.currentTarget as HTMLButtonElement);
}
</script>

<template>
  <section class="mt-9">
    <div class="mb-4 flex flex-wrap items-center justify-between gap-2">
      <h2 class="text-lg font-semibold tracking-tight">Activity <span class="ml-2 rounded-full bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-400">{{ transactions.length }}</span></h2>
      <p class="text-xs text-slate-500">Select a transaction to explore its flow</p>
    </div>
    <ul v-if="transactions.length" class="grid min-w-0 gap-3 lg:hidden" aria-label="Transactions">
      <li v-for="tx in transactions" :key="tx.txid" class="wallet-panel min-w-0 p-4">
        <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
          <TransactionBadge :type="tx.type" />
          <span class="text-sm font-semibold tabular-nums" :class="tx.amount >= 0 ? 'text-emerald-400' : 'text-rose-400'">
            {{ amount(tx.amount, true) }} {{ currencyLabel(currency) }}
          </span>
        </div>
        <dl class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-2 text-sm">
          <dt class="text-slate-400">Transaction</dt>
          <dd class="text-right">
            <button type="button" @click="inspect(tx, $event)" aria-haspopup="dialog" aria-controls="transaction-details-dialog" :aria-label="`View transaction ${tx.txid}`" class="break-all rounded-lg font-mono text-sky-400 hover:text-sky-300 hover:underline">
              {{ shortId(tx.txid) }}
            </button>
          </dd>
          <dt class="text-slate-400">Date</dt>
          <dd class="text-right text-slate-300">{{ formatDate(tx.timestamp) }}</dd>
          <dt class="text-slate-400">Confirmations</dt>
          <dd class="text-right"><ConfirmationStatus :confirmations="tx.confirmations" /></dd>
        </dl>
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
          <tr v-for="tx in transactions" :key="tx.txid" class="border-t border-slate-800 hover:bg-slate-800/50">
            <td class="px-3 py-2.5"><TransactionBadge :type="tx.type" /></td>
            <td class="px-3 py-2.5">
              <button type="button" @click="inspect(tx, $event)" aria-haspopup="dialog" aria-controls="transaction-details-dialog" :aria-label="`View transaction ${tx.txid}`" class="rounded-lg font-mono text-sky-400 hover:text-sky-300 hover:underline">
                {{ shortId(tx.txid) }}
              </button>
            </td>
            <td class="px-3 py-2.5 text-slate-300">{{ formatDate(tx.timestamp) }}</td>
            <td class="px-3 py-2.5 text-right tabular-nums" :class="tx.amount >= 0 ? 'text-emerald-400' : 'text-rose-400'">
              {{ amount(tx.amount, true) }}
            </td>
            <td class="px-3 py-2.5 text-right"><ConfirmationStatus :confirmations="tx.confirmations" compact /></td>
            <td class="px-3 py-2.5 text-right"><button type="button" @click="inspect(tx, $event)" aria-haspopup="dialog" aria-controls="transaction-details-dialog" :aria-label="`Explore transaction ${tx.txid}`" class="rounded-lg px-2 text-xs text-slate-400 transition hover:bg-accent/10 hover:text-accent">Explore <span aria-hidden="true">↗</span></button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="wallet-panel p-10 text-center"><p class="text-sm text-slate-300">No activity yet</p><p class="mt-2 text-xs text-slate-500">Transactions will appear here as your wallet synchronizes.</p></div>
  </section>
</template>