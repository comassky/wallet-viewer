<script setup lang="ts">
import type { Currency } from '../currency';
import type { Utxo } from '../types/wallet';
import { shortId } from '../utils/format';
import ConfirmationStatus from './ConfirmationStatus.vue';

defineProps<{
  utxos: Utxo[];
  currency: Currency;
  amount: (sats: number, signed?: boolean) => string;
}>();
</script>

<template>
  <section class="mt-6">
    <h2 class="mb-3 text-xs font-medium uppercase tracking-wider text-slate-400">
      UTXOs ({{ utxos.length }})
    </h2>
    <ul v-if="utxos.length" class="grid min-w-0 gap-3 lg:hidden" aria-label="UTXOs">
      <li v-for="u in utxos" :key="`${u.txid}:${u.vout}`" class="min-w-0 rounded-xl border border-slate-800 bg-slate-900 p-4">
        <p class="mb-3 text-sm font-semibold tabular-nums">{{ amount(u.value) }} {{ currency }}</p>
        <dl class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-2 text-sm">
          <dt class="text-slate-400">Address</dt>
          <dd class="break-all text-right font-mono text-slate-300">{{ shortId(u.address) }}</dd>
          <dt class="text-slate-400">Outpoint</dt>
          <dd class="break-all text-right font-mono text-slate-300">{{ shortId(u.txid) }}:{{ u.vout }}</dd>
          <dt class="text-slate-400">Confirmations</dt>
          <dd class="text-right"><ConfirmationStatus :confirmations="u.confirmations" /></dd>
        </dl>
      </li>
    </ul>
    <div v-if="utxos.length" class="hidden rounded-xl border border-slate-800 bg-slate-900 lg:block">
      <table class="w-full table-fixed text-sm">
        <thead>
          <tr class="text-xs uppercase text-slate-400">
            <th class="px-3 py-3 text-left font-medium">Address</th>
            <th class="px-3 py-3 text-left font-medium">Outpoint</th>
            <th class="px-3 py-3 text-right font-medium">Value ({{ currency }})</th>
            <th class="px-3 py-3 text-right font-medium">Confirmations</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in utxos" :key="`${u.txid}:${u.vout}`" class="border-t border-slate-800 hover:bg-slate-800/50">
            <td class="px-3 py-2.5 font-mono text-slate-300">{{ shortId(u.address) }}</td>
            <td class="px-3 py-2.5 font-mono text-slate-300">{{ shortId(u.txid) }}:{{ u.vout }}</td>
            <td class="px-3 py-2.5 text-right tabular-nums">{{ amount(u.value) }}</td>
            <td class="px-3 py-2.5 text-right"><ConfirmationStatus :confirmations="u.confirmations" compact /></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="rounded-xl border border-slate-800 bg-slate-900 p-5 text-slate-400">No UTXOs.</div>
  </section>
</template>