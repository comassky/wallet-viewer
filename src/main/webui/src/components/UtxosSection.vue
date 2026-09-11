<script setup lang="ts">
import { toRef } from 'vue';
import { currencyLabel, type Currency } from '@/currency';
import type { Utxo } from '@/types/wallet';
import { shortId } from '@/utils/format';
import ConfirmationStatus from '@/components/ConfirmationStatus.vue';
import CopyValue from '@/components/CopyValue.vue';
import UiIcon from '@/components/UiIcon.vue';
import { useTableSort, type SortColumn } from '@/composables/useTableSort';

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
    <div class="mb-4 flex items-center gap-2.5">
      <UiIcon name="coins" class="text-accent" />
      <h2 class="text-sm font-semibold text-slate-200">Unspent outputs</h2>
      <span class="rounded-md border border-slate-700/60 px-2 py-0.5 text-xs tabular-nums text-slate-400">{{ utxos.length }}</span>
    </div>
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
        <div class="mb-4 flex items-start justify-between gap-3">
          <p class="sensitive min-w-0 break-words text-base font-semibold tabular-nums text-emerald-300">{{ amount(u.value) }} <span class="text-xs font-medium text-slate-400">{{ currencyLabel(currency) }}</span></p>
          <UiIcon name="coins" class="mt-1 text-accent" />
        </div>
        <dl class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-2 text-sm">
          <dt class="text-slate-400">Address</dt>
          <dd class="text-right"><CopyValue :value="u.address" :display="shortId(u.address)" label="address" /></dd>
          <dt class="text-slate-400">Outpoint</dt>
          <dd class="text-right"><CopyValue :value="u.txid" :display="shortId(u.txid)" label="transaction ID" /><span class="ml-1 inline-block rounded border border-sky-400/20 bg-sky-400/5 px-1.5 font-mono text-xs text-sky-300">:{{ u.vout }}</span></dd>
          <dt class="text-slate-400">Confirmations</dt>
          <dd class="text-right"><span class="inline-flex items-center gap-1.5" :class="{ 'rounded-md bg-emerald-400/10 px-2 py-0.5': u.confirmations >= 5 }"><UiIcon v-if="u.confirmations >= 5" name="shield" class="text-emerald-400" /><ConfirmationStatus :confirmations="u.confirmations" /></span></dd>
        </dl>
      </li>
    </ul>
    <div v-if="utxos.length" class="wallet-panel hidden lg:block">
      <table class="w-full table-fixed text-sm">
        <colgroup><col class="w-[30%]" /><col class="w-[32%]" /><col class="w-[22%]" /><col class="w-[16%]" /></colgroup>
        <thead class="bg-slate-800/40">
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
          <tr v-for="u in sorted" :key="`${u.txid}:${u.vout}`" class="border-t border-slate-800 transition-colors even:bg-slate-800/15 hover:bg-slate-800/50">
            <td class="px-3 py-3"><div class="flex min-w-0 items-center gap-3"><span class="flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-accent/20 bg-accent/5 text-accent"><UiIcon name="coins" /></span><CopyValue :value="u.address" :display="shortId(u.address)" label="address" /></div></td>
            <td class="px-3 py-3"><CopyValue :value="u.txid" :display="shortId(u.txid)" label="transaction ID" /><span class="ml-2 inline-block rounded border border-sky-400/20 bg-sky-400/5 px-1.5 font-mono text-xs text-sky-300">:{{ u.vout }}</span></td>
            <td class="sensitive px-3 py-3 text-right font-semibold tabular-nums text-emerald-300">{{ amount(u.value) }}</td>
            <td class="px-3 py-3 text-right"><span class="inline-flex items-center gap-1.5" :class="{ 'rounded-md bg-emerald-400/10 px-2 py-1': u.confirmations >= 5 }"><UiIcon v-if="u.confirmations >= 5" name="shield" class="text-emerald-400" /><ConfirmationStatus :confirmations="u.confirmations" compact /></span></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="wallet-panel p-8 text-center text-sm text-slate-400">No unspent outputs in this wallet.</div>
  </section>
</template>