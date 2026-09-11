<script setup lang="ts">
import { computed, toRef } from 'vue';
import { currencyLabel, type Currency } from '@/currency';
import type { Transaction, Utxo } from '@/types/wallet';
import { formatDate, shortId } from '@/utils/format';
import ConfirmationStatus from '@/components/ConfirmationStatus.vue';
import CopyValue from '@/components/CopyValue.vue';
import UiIcon from '@/components/UiIcon.vue';
import { useTableSort, type SortColumn } from '@/composables/useTableSort';

const props = defineProps<{
  utxos: Utxo[];
  transactions: Transaction[];
  currency: Currency;
  amount: (sats: number, signed?: boolean) => string;
}>();
const timestampByTxid = computed(() => new Map(props.transactions.map(transaction => [transaction.txid, transaction.timestamp])));
const confirmationDate = (output: Utxo) => output.confirmations > 0 ? timestampByTxid.value.get(output.txid) ?? null : null;
const columns: SortColumn<Utxo>[] = [
  { key: 'address', label: 'Address', value: u => u.address },
  { key: 'outpoint', label: 'Outpoint', value: u => u.txid, secondary: u => u.vout },
  { key: 'date', label: 'Date', value: confirmationDate },
  { key: 'value', label: 'Value', value: u => u.value, numeric: true },
  { key: 'share', label: 'Share (%)', value: u => u.value, numeric: true },
  { key: 'confirmations', label: 'Confirmations', value: u => u.confirmations, numeric: true },
];
const { sorted, sortKey, descending, toggleSort, ariaSort } = useTableSort(toRef(props, 'utxos'), columns);
const totalValue = computed(() => props.utxos.reduce((total, output) => total + output.value, 0));
const valueShare = (value: number) => totalValue.value > 0 ? value / totalValue.value * 100 : 0;
const shareFormatter = new Intl.NumberFormat(undefined, { style: 'percent', minimumFractionDigits: 2, maximumFractionDigits: 2 });
const formatShare = (value: number) => shareFormatter.format(valueShare(value) / 100);
</script>

<template>
  <section>
    <div class="mb-4 flex flex-wrap items-center justify-between gap-4">
      <div class="flex items-center gap-2.5">
        <UiIcon name="coins" class="text-accent" />
        <h2 class="text-sm font-semibold text-slate-200">Unspent outputs</h2>
        <span class="rounded-md border border-accent/25 bg-accent/10 px-2 py-0.5 text-xs font-semibold tabular-nums text-accent">{{ utxos.length }}</span>
      </div>
      <p v-if="utxos.length" class="flex min-w-0 flex-wrap items-baseline gap-x-2 text-xs text-slate-400">
        Total value
        <span class="sensitive break-all text-lg font-semibold tabular-nums text-slate-100">{{ amount(totalValue) }} <span class="text-xs font-medium text-slate-400">{{ currencyLabel(currency) }}</span></span>
      </p>
    </div>
    <div class="mb-4 flex min-w-0 flex-wrap items-center gap-2 text-xs xl:hidden">
      <label>Sort by
        <select v-model="sortKey" class="ml-2 min-h-11 max-w-48 rounded-lg border border-slate-700 bg-slate-900 px-2 text-base sm:text-sm">
          <option value="">Default order</option>
          <option v-for="column in columns" :key="column.key" :value="column.key">{{ column.label }}</option>
        </select>
      </label>
      <button v-if="sortKey" type="button" class="button-secondary rounded-lg px-3" :aria-label="descending ? 'Sort ascending' : 'Sort descending'" @click="descending = !descending">{{ descending ? '↓' : '↑' }}</button>
    </div>
    <ul v-if="utxos.length" class="grid min-w-0 gap-3 md:grid-cols-2 xl:hidden" aria-label="UTXOs">
      <li v-for="u in sorted" :key="`${u.txid}:${u.vout}`" class="wallet-panel min-w-0 border-t-2 p-4" :class="u.confirmations === 0 ? 'border-t-amber-400/70' : 'border-t-emerald-400/60'">
        <div class="mb-3 flex items-start justify-between gap-3">
          <p class="sensitive min-w-0 break-words text-xl font-semibold tabular-nums text-slate-100">{{ amount(u.value) }} <span class="text-xs font-medium text-slate-400">{{ currencyLabel(currency) }}</span></p>
          <UiIcon name="coins" class="mt-1 shrink-0" :class="u.confirmations === 0 ? 'text-amber-300' : 'text-emerald-300'" />
        </div>
        <div class="sensitive mb-4 h-1 overflow-hidden rounded-full bg-slate-700/50" aria-hidden="true"><div class="h-full rounded-full" :class="u.confirmations === 0 ? 'bg-amber-400' : 'bg-emerald-400'" :style="{ width: `${valueShare(u.value)}%` }" /></div>
        <dl class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-2 text-sm">
          <dt class="text-slate-400">Share of total</dt>
          <dd class="sensitive text-right font-medium tabular-nums text-slate-200">{{ formatShare(u.value) }}</dd>
          <dt class="text-slate-400">Address</dt>
          <dd class="text-right"><CopyValue :value="u.address" :display="shortId(u.address)" label="address" /></dd>
          <dt class="text-slate-400">Outpoint</dt>
          <dd class="text-right"><CopyValue :value="u.txid" :display="shortId(u.txid)" label="transaction ID" /><span class="ml-1 inline-block rounded border border-sky-400/20 bg-sky-400/5 px-1.5 font-mono text-xs text-sky-300">:{{ u.vout }}</span></dd>
          <dt class="text-slate-400">Confirmations</dt>
          <dd class="text-right"><ConfirmationStatus :confirmations="u.confirmations" icon-only /></dd>
          <dt class="text-slate-400">Confirmation date</dt>
          <dd class="text-right text-slate-300">{{ formatDate(confirmationDate(u)) }}</dd>
        </dl>
      </li>
    </ul>
    <div v-if="utxos.length" class="wallet-panel hidden xl:block">
      <table class="w-full table-fixed text-sm">
        <colgroup><col class="w-[24%]" /><col class="w-[24%]" /><col class="w-[15%]" /><col class="w-[14%]" /><col class="w-[10%]" /><col class="w-[13%]" /></colgroup>
        <thead class="border-b border-slate-600/50 bg-slate-800/80">
          <tr class="text-xs uppercase text-slate-300">
            <th v-for="column in columns" :key="column.key" scope="col" :aria-sort="ariaSort(column.key)" class="px-4 py-2 font-medium" :class="column.numeric ? 'text-right' : 'text-left'">
              <button type="button" class="w-full rounded-sm py-2 text-inherit transition hover:text-accent" :class="column.numeric ? 'text-right' : 'text-left'" @click="toggleSort(column.key)">
                {{ column.label }}{{ column.key === 'value' ? ` (${currencyLabel(currency)})` : '' }}
                <span aria-hidden="true" class="ml-1" :class="sortKey === column.key ? 'text-accent' : 'text-slate-600'">{{ sortKey === column.key ? (descending ? '↓' : '↑') : '↕' }}</span>
              </button>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in sorted" :key="`${u.txid}:${u.vout}`" class="group border-t border-slate-700/40 transition-colors hover:bg-slate-700/35 focus-within:bg-slate-700/35" :class="u.confirmations === 0 ? 'bg-amber-400/[0.04]' : 'even:bg-slate-800/30'">
            <td class="border-l-2 px-4 py-4" :class="u.confirmations === 0 ? 'border-l-amber-400/70' : 'border-l-emerald-400/50'"><div class="flex min-w-0 items-center gap-3"><span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-md border" :class="u.confirmations === 0 ? 'border-amber-400/25 bg-amber-400/10 text-amber-300' : 'border-emerald-400/25 bg-emerald-400/10 text-emerald-300'"><UiIcon name="coins" /></span><CopyValue :value="u.address" :display="shortId(u.address)" label="address" /></div></td>
            <td class="px-4 py-4"><div class="flex items-center gap-2"><CopyValue :value="u.txid" :display="shortId(u.txid)" label="transaction ID" /><span class="shrink-0 rounded border border-sky-400/25 bg-sky-400/10 px-1.5 font-mono text-xs text-sky-300">:{{ u.vout }}</span></div></td>
            <td class="px-4 py-4 text-slate-300">{{ formatDate(confirmationDate(u)) }}</td>
            <td class="sensitive px-4 py-4 text-right tabular-nums">
              <div class="break-all text-base font-semibold text-slate-100">{{ amount(u.value) }}</div>
            </td>
            <td class="sensitive px-4 py-4 text-right tabular-nums">
              <div class="font-medium text-slate-200">{{ formatShare(u.value) }}</div>
              <div class="mt-2 ml-auto h-1 w-full max-w-40 overflow-hidden rounded-full bg-slate-700/60" aria-hidden="true"><div class="ml-auto h-full rounded-full" :class="u.confirmations === 0 ? 'bg-amber-400' : 'bg-emerald-400'" :style="{ width: `${valueShare(u.value)}%` }" /></div>
            </td>
            <td class="px-4 py-4 text-right"><ConfirmationStatus :confirmations="u.confirmations" icon-only /></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="wallet-panel p-8 text-center text-sm text-slate-400">No unspent outputs in this wallet.</div>
  </section>
</template>