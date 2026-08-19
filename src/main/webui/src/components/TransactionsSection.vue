<script setup lang="ts">
import type { Currency } from '../currency';
import type { Transaction } from '../types/wallet';
import { shortId, formatDate } from '../utils/format';
import ConfirmationStatus from './ConfirmationStatus.vue';
import TransactionBadge from './TransactionBadge.vue';

defineProps<{
  transactions: Transaction[];
  currency: Currency;
  amount: (sats: number, signed?: boolean) => string;
}>();
</script>

<template>
  <section class="mt-6">
    <h2 class="mb-3 text-xs font-medium uppercase tracking-wider text-slate-400">
      Transactions ({{ transactions.length }})
    </h2>
    <ul v-if="transactions.length" class="grid min-w-0 gap-3 lg:hidden" aria-label="Transactions">
      <li v-for="tx in transactions" :key="tx.txid" class="min-w-0 rounded-xl border border-slate-800 bg-slate-900 p-4">
        <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
          <TransactionBadge :type="tx.type" />
          <span class="text-sm font-semibold tabular-nums" :class="tx.amount >= 0 ? 'text-emerald-400' : 'text-rose-400'">
            {{ amount(tx.amount, true) }} {{ currency }}
          </span>
        </div>
        <dl class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-2 text-sm">
          <dt class="text-slate-400">Transaction</dt>
          <dd class="text-right">
            <a :href="`https://mempool.space/tx/${tx.txid}`" target="_blank" rel="noopener noreferrer" class="break-all font-mono text-sky-400 hover:underline">
              {{ shortId(tx.txid) }}
            </a>
          </dd>
          <dt class="text-slate-400">Date</dt>
          <dd class="text-right text-slate-300">{{ formatDate(tx.timestamp) }}</dd>
          <dt class="text-slate-400">Confirmations</dt>
          <dd class="text-right"><ConfirmationStatus :confirmations="tx.confirmations" /></dd>
        </dl>
      </li>
    </ul>
    <div v-if="transactions.length" class="hidden rounded-xl border border-slate-800 bg-slate-900 lg:block">
      <table class="w-full table-fixed text-sm">
        <thead>
          <tr class="text-xs uppercase text-slate-400">
            <th class="px-3 py-3 text-left font-medium">Type</th>
            <th class="px-3 py-3 text-left font-medium">Transaction</th>
            <th class="px-3 py-3 text-left font-medium">Date</th>
            <th class="px-3 py-3 text-right font-medium">Amount ({{ currency }})</th>
            <th class="px-3 py-3 text-right font-medium">Confirmations</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="tx in transactions" :key="tx.txid" class="border-t border-slate-800 hover:bg-slate-800/50">
            <td class="px-3 py-2.5"><TransactionBadge :type="tx.type" /></td>
            <td class="px-3 py-2.5">
              <a :href="`https://mempool.space/tx/${tx.txid}`" target="_blank" rel="noopener noreferrer" class="font-mono text-sky-400 hover:underline">
                {{ shortId(tx.txid) }}
              </a>
            </td>
            <td class="px-3 py-2.5 text-slate-300">{{ formatDate(tx.timestamp) }}</td>
            <td class="px-3 py-2.5 text-right tabular-nums" :class="tx.amount >= 0 ? 'text-emerald-400' : 'text-rose-400'">
              {{ amount(tx.amount, true) }}
            </td>
            <td class="px-3 py-2.5 text-right"><ConfirmationStatus :confirmations="tx.confirmations" compact /></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="rounded-xl border border-slate-800 bg-slate-900 p-5 text-slate-400">No transactions.</div>
  </section>
</template>