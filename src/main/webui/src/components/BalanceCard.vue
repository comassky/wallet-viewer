<script setup lang="ts">
import { ref, watch } from 'vue';
import { currencyLabel, formatAmount, type BitcoinUnit, type FiatCurrency } from '../currency';
import type { Balance, PriceRates } from '../types/wallet';
import { formatDate } from '../utils/format';
import AppTooltip from './AppTooltip.vue';
import UiIcon from './UiIcon.vue';

const props = defineProps<{
  balance: Balance;
  currency: BitcoinUnit;
  fiatCurrency: FiatCurrency;
  rates: PriceRates | null;
  ratesLoading: boolean;
  ratesError: boolean;
  amount: (sats: number, signed?: boolean) => string;
}>();
defineEmits<{ 'update:currency': [value: BitcoinUnit]; 'update:fiatCurrency': [value: FiatCurrency] }>();
const bitcoinUnits = ['BTC', 'SATS'] as const;
const fiatCurrencies = ['EUR', 'USD'] as const;

// Pop the total whenever the balance changes (a new transaction moved funds).
const pop = ref(false);
watch(() => props.balance.total, (value, previous) => {
  if (previous === undefined || value === previous) return;
  pop.value = false;
  requestAnimationFrame(() => { pop.value = true; });
});

// Hover hint for a fiat unit: current BTC quote, its timestamp and source.
function priceTitle(unit: FiatCurrency): string | undefined {
  if (!props.rates) return undefined;
  return `1 BTC ≈ ${formatAmount(100_000_000, unit, props.rates)} ${unit} · As of ${formatDate(props.rates.timestamp)} · Source: mempool.space (current price, not historical)`;
}
</script>

<template>
  <section class="wallet-panel balance-card min-w-0 p-4 sm:p-6">
    <div class="mb-4 flex flex-wrap items-center justify-between gap-x-2 gap-y-3 sm:mb-5">
      <h2 class="section-title flex items-center gap-2"><UiIcon name="coins" class="text-accent" />Total balance</h2>
      <div class="inline-flex shrink-0 rounded-xl border border-slate-700/60 bg-slate-950/60 p-0.5" role="group" aria-label="Bitcoin display unit">
          <button
            v-for="unit in bitcoinUnits" :key="unit"
            type="button"
            :aria-pressed="currency === unit"
            class="rounded-lg px-2.5 py-1 text-xs font-semibold tracking-wide transition"
            :class="currency === unit ? 'bg-accent text-slate-950' : 'text-slate-400 hover:text-slate-200'"
            @click="$emit('update:currency', unit)"
          >{{ currencyLabel(unit) }}</button>
      </div>
    </div>
    <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1">
      <span class="sensitive min-w-0 break-words font-semibold tabular-nums sm:text-5xl" :class="[{ 'value-pop': pop }, currency === 'SATS' ? 'text-2xl' : 'text-3xl']" @animationend="pop = false">{{ amount(balance.total) }}</span>
      <span class="text-lg font-medium text-accent">{{ currencyLabel(currency) }}</span>
    </div>
    <div class="mt-3 flex flex-wrap items-center justify-between gap-3">
      <p class="text-lg tabular-nums text-slate-300"><span v-if="rates">≈ </span><span class="sensitive">{{ formatAmount(balance.total, fiatCurrency, rates) }}</span> <span class="text-sm">{{ fiatCurrency }}</span></p>
      <div role="group" aria-label="Fiat estimate currency" class="inline-flex rounded-lg border border-slate-700/60 bg-slate-950/40 p-0.5">
        <AppTooltip v-for="unit in fiatCurrencies" :key="unit" :text="priceTitle(unit)">
          <button type="button" :aria-pressed="fiatCurrency === unit" class="rounded-md px-2.5 text-xs font-semibold transition" :class="fiatCurrency === unit ? 'bg-slate-700 text-slate-100' : 'text-slate-400 hover:text-slate-200'" @click="$emit('update:fiatCurrency', unit)">{{ unit }}</button>
        </AppTooltip>
      </div>
    </div>
    <p role="status" class="mt-2 text-xs" :class="ratesError ? 'text-amber-300' : 'text-slate-400'">
      <template v-if="ratesError">{{ rates ? 'Price update unavailable · showing the last quote.' : 'Fiat estimate unavailable · Bitcoin amounts remain up to date.' }}</template>
      <template v-else-if="!rates">{{ ratesLoading ? 'Loading current Bitcoin price…' : 'Waiting for a Bitcoin price quote…' }}</template>
      <template v-else>Estimate at the current price · preferences saved locally</template>
    </p>
    <p v-if="rates" class="mt-1 text-xs text-slate-400">Quote: {{ formatDate(rates.timestamp) }} · mempool.space</p>
    <dl class="mt-4 grid grid-cols-2 gap-3 border-t border-slate-700/40 pt-4 sm:mt-6 sm:gap-4 sm:pt-5">
      <div class="min-w-0">
        <dt class="mb-1.5 flex items-center gap-2 text-xs text-slate-400"><span class="h-1.5 w-1.5 rounded-full bg-emerald-400" />Confirmed</dt>
        <dd class="sensitive break-all text-sm font-medium tabular-nums">{{ amount(balance.confirmed) }} <span class="text-slate-500">{{ currencyLabel(currency) }}</span></dd>
      </div>
      <div class="min-w-0">
        <dt class="mb-1.5 flex items-center gap-2 text-xs text-slate-400"><span class="h-1.5 w-1.5 rounded-full bg-amber-400" />Pending</dt>
        <dd class="sensitive break-all text-sm font-medium tabular-nums">{{ amount(balance.unconfirmed) }} <span class="text-slate-500">{{ currencyLabel(currency) }}</span></dd>
      </div>
    </dl>
  </section>
</template>
