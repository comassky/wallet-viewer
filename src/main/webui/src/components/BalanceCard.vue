<script setup lang="ts">
import type { Currency } from '../currency';
import type { Balance } from '../types/wallet';

defineProps<{
  balance: Balance;
  currency: Currency;
  estimated: boolean;
  amount: (sats: number, signed?: boolean) => string;
}>();
</script>

<template>
  <section class="min-w-0 rounded-xl border border-slate-800 bg-slate-900 p-4 sm:p-5">
    <h2 class="mb-3.5 text-xs font-medium uppercase tracking-wider text-slate-400">Balance</h2>
    <div class="break-words text-3xl font-semibold tabular-nums sm:text-4xl">
      {{ estimated ? '≈ ' : '' }}{{ amount(balance.total) }}<span class="ml-1.5 text-base text-slate-400">{{ currency }}</span>
    </div>
    <div class="mt-2 flex flex-wrap gap-5 text-sm text-slate-400">
      <span>Confirmed: <b class="font-semibold text-slate-100">{{ amount(balance.confirmed) }} {{ currency }}</b></span>
      <span v-if="balance.unconfirmed !== 0">
        Unconfirmed: <b class="font-semibold text-slate-100">{{ amount(balance.unconfirmed) }} {{ currency }}</b>
      </span>
    </div>
  </section>
</template>