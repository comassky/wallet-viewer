<script setup lang="ts">
import type { Currency } from '../currency';
import type { PriceRates } from '../types/wallet';
import { formatDate } from '../utils/format';

defineProps<{
  currency: Currency;
  rates: PriceRates | null;
  loading: boolean;
  error: boolean;
  amount: (sats: number, signed?: boolean) => string;
}>();
defineEmits<{ retry: [] }>();
</script>

<template>
  <div role="status" class="mb-4 rounded-lg border border-slate-800 bg-slate-900 px-4 py-3 text-xs text-slate-400">
    <template v-if="rates">
      <span :class="error ? 'text-amber-400' : ''">
        {{ error ? 'Price unavailable — showing the last estimate.' : 'Estimated conversion at the current price, not the historical transaction price.' }}
      </span>
      <span class="mt-1 block">1 BTC ≈ {{ amount(100_000_000) }} {{ currency }} · As of {{ formatDate(rates.timestamp) }} · Source: mempool.space</span>
    </template>
    <span v-else-if="loading">Loading BTC price…</span>
    <span v-else class="text-amber-400">Price unavailable. Amounts can still be viewed in BTC or SATS.</span>
    <button v-if="error" type="button" @click="$emit('retry')" :disabled="loading" class="mt-2 text-sky-400 underline disabled:opacity-50">
      {{ loading ? 'Loading…' : 'Retry (server cache: 60 s)' }}
    </button>
  </div>
</template>