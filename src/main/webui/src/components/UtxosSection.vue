<script setup lang="ts">
import { currencyLabel, type Currency } from '../currency';
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
  <section class="mt-9">
    <h2 class="mb-4 text-lg font-semibold tracking-tight">
      Unspent outputs <span class="ml-2 rounded-full bg-slate-800 px-2.5 py-1 text-xs font-medium text-slate-400">{{ utxos.length }}</span>
    </h2>
    <ul v-if="utxos.length" class="grid min-w-0 gap-3 lg:hidden" aria-label="UTXOs">
      <li v-for="u in utxos" :key="`${u.txid}:${u.vout}`" class="wallet-panel min-w-0 p-4">
        <p class="mb-3 text-sm font-semibold tabular-nums">{{ amount(u.value) }} {{ currencyLabel(currency) }}</p>
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
    <div v-if="utxos.length" class="wallet-panel hidden lg:block">
      <table class="w-full table-fixed text-sm">
        <thead>
          <tr class="text-xs uppercase text-slate-400">
            <th class="px-3 py-3 text-left font-medium">Address</th>
            <th class="px-3 py-3 text-left font-medium">Outpoint</th>
            <th class="px-3 py-3 text-right font-medium">Value ({{ currencyLabel(currency) }})</th>
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
    <div v-else class="wallet-panel p-8 text-center text-sm text-slate-400">No unspent outputs in this wallet.</div>
  </section>
</template>