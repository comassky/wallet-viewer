<script setup lang="ts">
import { currencies, currencyLabel, type Currency } from '../currency';
import type { Balance } from '../types/wallet';
import UiIcon from './UiIcon.vue';

defineProps<{
  balance: Balance;
  currency: Currency;
  estimated: boolean;
  amount: (sats: number, signed?: boolean) => string;
}>();
defineEmits<{ 'update:currency': [value: Currency] }>();
</script>

<template>
  <section class="wallet-panel balance-card min-w-0 p-5 sm:p-6">
    <div class="mb-5 flex flex-wrap items-center justify-between gap-x-4 gap-y-3">
      <h2 class="section-title flex items-center gap-2"><UiIcon name="coins" class="text-accent" />Total balance</h2>
      <div class="inline-flex shrink-0 rounded-xl border border-slate-700/60 bg-slate-950/60 p-0.5" role="group" aria-label="Display currency">
        <button
          v-for="unit in currencies"
          :key="unit"
          type="button"
          :aria-pressed="currency === unit"
          class="rounded-lg px-2.5 text-xs font-semibold tracking-wide transition"
          :class="currency === unit ? 'bg-accent text-slate-950' : 'text-slate-400 hover:text-slate-200'"
          @click="$emit('update:currency', unit)"
        >{{ currencyLabel(unit) }}</button>
      </div>
    </div>
    <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1">
      <span class="break-all text-4xl font-semibold tracking-tight tabular-nums sm:text-5xl">{{ estimated ? '≈ ' : '' }}{{ amount(balance.total) }}</span>
      <span class="text-lg font-medium text-accent">{{ currencyLabel(currency) }}</span>
    </div>
    <p class="mt-2 text-xs text-slate-500">Display currency · saved locally</p>
    <dl class="mt-6 grid gap-4 border-t border-slate-700/40 pt-5 sm:grid-cols-2">
      <div>
        <dt class="mb-1.5 flex items-center gap-2 text-xs text-slate-400"><span class="h-1.5 w-1.5 rounded-full bg-emerald-400" />Confirmed</dt>
        <dd class="break-all text-sm font-medium tabular-nums">{{ amount(balance.confirmed) }} <span class="text-slate-500">{{ currencyLabel(currency) }}</span></dd>
      </div>
      <div>
        <dt class="mb-1.5 flex items-center gap-2 text-xs text-slate-400"><span class="h-1.5 w-1.5 rounded-full bg-amber-400" />Pending</dt>
        <dd class="break-all text-sm font-medium tabular-nums">{{ amount(balance.unconfirmed) }} <span class="text-slate-500">{{ currencyLabel(currency) }}</span></dd>
      </div>
    </dl>
  </section>
</template>
