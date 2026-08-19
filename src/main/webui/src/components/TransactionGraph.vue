<script setup lang="ts">
import { computed } from 'vue';
import { currencyLabel, type Currency } from '../currency';
import type { TransactionDetails } from '../types/wallet';
import { shortId } from '../utils/format';
import { transactionGraphLayout } from '../utils/transactionGraph';

const props = defineProps<{
  idPrefix: string;
  details: TransactionDetails;
  currency: Currency;
  amount: (sats: number, signed?: boolean) => string;
}>();
const graph = computed(() => transactionGraphLayout(props.details.inputs.length, props.details.outputs.length));
</script>

<template>
  <section :aria-labelledby="`${idPrefix}-graph-heading`">
    <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
      <h4 :id="`${idPrefix}-graph-heading`" class="section-title">Transaction flow</h4>
      <p class="text-xs text-slate-500">All inputs & outputs · scroll to explore</p>
    </div>
    <div class="mb-2 flex justify-between px-2 text-xs font-medium">
      <span class="text-sky-400">{{ details.inputs.length }} inputs</span>
      <span class="text-emerald-400">{{ details.outputs.length }} outputs</span>
    </div>
    <div class="max-h-[28rem] overflow-auto rounded-2xl border border-slate-700/50 bg-slate-950/60" tabindex="0" role="region" aria-label="Scrollable transaction graph; full values are listed below">
      <svg :viewBox="`0 0 ${graph.width} ${graph.height}`" :style="{ height: `${graph.height}px` }" class="w-full min-w-[960px]" role="img" :aria-labelledby="`${idPrefix}-graph-title ${idPrefix}-graph-description`">
        <title :id="`${idPrefix}-graph-title`">Inputs to transaction to outputs</title>
        <desc :id="`${idPrefix}-graph-description`">{{ details.inputs.length }} inputs connect to transaction {{ details.txid }}, then to {{ details.outputs.length }} outputs. Connections indicate structure, not an allocation of particular inputs to particular outputs. Full addresses and amounts are listed below.</desc>
        <path v-for="(node, index) in graph.inputs" :key="`in-${index}`" :d="node.path" fill="none" stroke="#38bdf8" stroke-opacity="0.35" stroke-width="2" />
        <path v-for="(node, index) in graph.outputs" :key="`out-${index}`" :d="node.path" fill="none" stroke="#34d399" stroke-opacity="0.35" stroke-width="2" />
        <g v-for="(input, index) in details.inputs" :key="index" :transform="`translate(16, ${graph.inputs[index].y - 28})`">
          <title>{{ input.coinbase ? 'Coinbase · newly created bitcoin' : (input.address || 'Non-address script') }} · {{ input.value === null ? 'Not applicable' : `${amount(input.value)} ${currencyLabel(currency)}` }}</title>
          <rect width="272" height="56" rx="12" fill="#0f172a" stroke="#38bdf8" stroke-opacity="0.3" />
          <circle cx="14" cy="18" r="3" fill="#38bdf8" />
          <text x="26" y="22" fill="#94a3b8" font-size="11" font-family="monospace">{{ input.coinbase ? 'Coinbase' : shortId(input.address || input.txid || 'Unknown script') }}</text>
          <text x="14" y="43" fill="#e2e8f0" font-size="12">{{ input.value === null ? 'Newly created bitcoin' : `${amount(input.value)} ${currencyLabel(currency)}` }}</text>
        </g>
        <g :transform="`translate(420, ${graph.centerY - 46})`">
          <rect width="120" height="92" rx="20" fill="#261e18" stroke="#f7931a" stroke-opacity="0.7" />
          <text x="60" y="32" text-anchor="middle" fill="#f7931a" font-size="25">₿</text>
          <text x="60" y="53" text-anchor="middle" fill="#f8fafc" font-size="11">Transaction</text>
          <text x="60" y="73" text-anchor="middle" fill="#94a3b8" font-size="10" font-family="monospace">{{ details.txid.slice(0, 8) }}…</text>
        </g>
        <g v-for="(output, index) in details.outputs" :key="output.index" :transform="`translate(672, ${graph.outputs[index].y - 28})`">
          <title>Output {{ output.index }} · {{ output.address || 'Non-address script' }} · {{ amount(output.value) }} {{ currencyLabel(currency) }}</title>
          <rect width="272" height="56" rx="12" fill="#0f172a" stroke="#34d399" stroke-opacity="0.3" />
          <circle cx="14" cy="18" r="3" fill="#34d399" />
          <text x="26" y="22" fill="#94a3b8" font-size="11" font-family="monospace">#{{ output.index }} {{ output.address ? shortId(output.address) : 'Non-address script' }}</text>
          <text x="14" y="43" fill="#e2e8f0" font-size="12">{{ amount(output.value) }} {{ currencyLabel(currency) }}</text>
        </g>
      </svg>
    </div>
    <p class="mt-2 text-xs leading-relaxed text-slate-500">The graph shows transaction structure, not which input funded which output. Network fees are the difference between total inputs and outputs.</p>
  </section>
</template>