<script setup lang="ts">
import { toRef } from 'vue';
import { currencyLabel, type Currency } from '../currency';
import type { Utxo } from '../types/wallet';
import { shortId } from '../utils/format';
import ConfirmationStatus from './ConfirmationStatus.vue';
import CopyValue from './CopyValue.vue';
import { useTableSort, type SortColumn } from '../composables/useTableSort';

const props = defineProps<{
  utxos: Utxo[];
  currency: Currency;
  amount: (sats: number, signed?: boolean) => string;
}>();
const columns: SortColumn<Utxo>[] = [
  { key: 'address', label: 'Address', value: u => u.address },
  { key: 'outpoint', label: 'Outpoint', value: u => u.txid, secondary: u => u.vout },
  { key: 'value', label: 'Value', value: u => u.value, numeric: true },
  { key: 'confirmations', label: 'Confirmations', value: u => u.confirmations, numeric: true },
];
const { sorted, sortKey, descending, toggleSort, ariaSort } = useTableSort(toRef(props, 'utxos'), columns);
</script>

<template>
  <section>
    <h2 class="sr-only">Unspent outputs</h2>
    <p class="mb-4 text-xs text-slate-500">Unspent outputs available in this wallet</p>
    <div class="mb-4 flex min-w-0 flex-wrap items-center gap-2 text-xs lg:hidden">
      <label>Sort by
        <select v-model="sortKey" class="ml-2 min-h-11 max-w-48 rounded-lg border border-slate-700 bg-slate-900 px-2 text-base sm:text-sm">
          <option value="">Default order</option>
          <option v-for="column in columns" :key="column.key" :value="column.key">{{ column.label }}</option>
        </select>
      </label>
      <button v-if="sortKey" type="button" class="button-secondary rounded-lg px-3" :aria-label="descending ? 'Sort ascending' : 'Sort descending'" @click="descending = !descending">{{ descending ? '↓' : '↑' }}</button>
    </div>
    <ul v-if="utxos.length" class="grid min-w-0 gap-3 md:grid-cols-2 lg:hidden" aria-label="UTXOs">
      <li v-for="u in sorted" :key="`${u.txid}:${u.vout}`" class="wallet-panel min-w-0 p-4">
        <p class="sensitive mb-3 text-sm font-semibold tabular-nums">{{ amount(u.value) }} {{ currencyLabel(currency) }}</p>
        <dl class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-2 text-sm">
          <dt class="text-slate-400">Address</dt>
          <dd class="text-right"><CopyValue :value="u.address" :display="shortId(u.address)" label="address" /></dd>
          <dt class="text-slate-400">Outpoint</dt>
          <dd class="text-right"><CopyValue :value="u.txid" :display="shortId(u.txid)" label="transaction ID" /><span class="font-mono">:{{ u.vout }}</span></dd>
          <dt class="text-slate-400">Confirmations</dt>
          <dd class="text-right"><ConfirmationStatus :confirmations="u.confirmations" /></dd>
        </dl>
      </li>
    </ul>
    <div v-if="utxos.length" class="wallet-panel hidden lg:block">
      <table class="w-full table-fixed text-sm">
        <thead>
          <tr class="text-xs uppercase text-slate-400">
            <th v-for="column in columns" :key="column.key" scope="col" :aria-sort="ariaSort(column.key)" class="px-3 py-1 font-medium" :class="column.numeric ? 'text-right' : 'text-left'">
              <button type="button" class="w-full rounded-sm py-2 text-inherit transition hover:text-accent" :class="column.numeric ? 'text-right' : 'text-left'" @click="toggleSort(column.key)">
                {{ column.label }}{{ column.key === 'value' ? ` (${currencyLabel(currency)})` : '' }}
                <span aria-hidden="true" class="ml-1" :class="sortKey === column.key ? 'text-accent' : 'text-slate-600'">{{ sortKey === column.key ? (descending ? '↓' : '↑') : '↕' }}</span>
              </button>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in sorted" :key="`${u.txid}:${u.vout}`" class="border-t border-slate-800 hover:bg-slate-800/50">
            <td class="px-3 py-2.5"><CopyValue :value="u.address" :display="shortId(u.address)" label="address" /></td>
            <td class="px-3 py-2.5"><CopyValue :value="u.txid" :display="shortId(u.txid)" label="transaction ID" /><span class="font-mono text-slate-300">:{{ u.vout }}</span></td>
            <td class="sensitive px-3 py-2.5 text-right tabular-nums">{{ amount(u.value) }}</td>
            <td class="px-3 py-2.5 text-right"><ConfirmationStatus :confirmations="u.confirmations" compact /></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="wallet-panel p-8 text-center text-sm text-slate-400">No unspent outputs in this wallet.</div>
  </section>
</template>