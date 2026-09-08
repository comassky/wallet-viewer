<script setup lang="ts">
import { useFees } from '../composables/useFees';
import { formatDate } from '../utils/format';
import UiIcon from './UiIcon.vue';

const { fees, loading, error } = useFees();
const tiers = [
  { key: 'fastest', label: 'Fast', dot: 'bg-emerald-400', value: 'text-emerald-400' },
  { key: 'halfHour', label: 'Medium', dot: 'bg-accent', value: 'text-accent' },
  { key: 'hour', label: 'Slow', dot: 'bg-slate-400', value: 'text-slate-200' },
] as const;
</script>

<template>
  <section class="wallet-panel flex flex-wrap items-center gap-x-6 gap-y-3 p-4 sm:px-6" aria-label="Recommended network fees">
    <h2 class="section-title flex items-center gap-2"><UiIcon name="graph" class="text-accent" />Network fees</h2>
    <div v-if="fees" class="flex flex-1 flex-wrap items-center justify-end gap-x-6 gap-y-2">
      <div v-for="tier in tiers" :key="tier.key" class="flex items-baseline gap-1.5">
        <span class="h-1.5 w-1.5 self-center rounded-full" :class="tier.dot" aria-hidden="true" />
        <span class="text-xs text-slate-400">{{ tier.label }}</span>
        <span class="tabular-nums text-sm font-semibold" :class="tier.value">{{ fees[tier.key] }}</span>
        <span class="text-xs text-slate-500">sat/vB</span>
      </div>
      <span class="text-[11px] text-slate-500" :title="`Updated ${formatDate(fees.timestamp)} · mempool.space`">mempool.space</span>
    </div>
    <p v-else class="flex-1 text-right text-xs" :class="error ? 'text-amber-300' : 'text-slate-400'">
      {{ error ? 'Fee estimates unavailable.' : (loading ? 'Loading network fees…' : 'Waiting for fee estimates…') }}
    </p>
  </section>
</template>
