<script setup lang="ts">
import { ref, watch } from 'vue';
import { currencies, currencyLabel, formatAmount, isFiat, type Currency } from '../currency';
import type { Balance, PriceRates } from '../types/wallet';
import { formatDate } from '../utils/format';
import UiIcon from './UiIcon.vue';

const props = defineProps<{
  balance: Balance;
  currency: Currency;
  estimated: boolean;
  rates: PriceRates | null;
  amount: (sats: number, signed?: boolean) => string;
}>();
defineEmits<{ 'update:currency': [value: Currency] }>();

// Pop the total whenever the balance changes (a new transaction moved funds).
const pop = ref(false);
watch(() => props.balance.total, (value, previous) => {
  if (previous === undefined || value === previous) return;
  pop.value = false;
  requestAnimationFrame(() => { pop.value = true; });
});

// Hover hint for a fiat unit: current BTC quote, its timestamp and source.
function priceTitle(unit: Currency): string | undefined {
  if (!isFiat(unit) || !props.rates) return undefined;
  return `1 BTC ≈ ${formatAmount(100_000_000, unit, props.rates)} ${unit} · As of ${formatDate(props.rates.timestamp)} · Source: mempool.space (current price, not historical)`;
}
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
          :title="priceTitle(unit)"
          class="rounded-lg px-2.5 text-xs font-semibold tracking-wide transition"
          :class="currency === unit ? 'bg-accent text-slate-950' : 'text-slate-400 hover:text-slate-200'"
          @click="$emit('update:currency', unit)"
        >{{ currencyLabel(unit) }}</button>
      </div>
    </div>
    <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1">
      <span class="break-all text-4xl font-semibold tracking-tight tabular-nums sm:text-5xl" :class="{ 'value-pop': pop }" @animationend="pop = false">{{ estimated ? '≈ ' : '' }}{{ amount(balance.total) }}</span>
      <span class="text-lg font-medium text-accent" :class="isFiat(currency) ? 'cursor-help' : ''" :title="priceTitle(currency)">{{ currencyLabel(currency) }}</span>
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
