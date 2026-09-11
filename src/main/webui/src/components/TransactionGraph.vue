<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { currencyLabel, type Currency } from '@/currency';
import type { TransactionDetails } from '@/types/wallet';
import { shortId } from '@/utils/format';
import { paginationRange, summarizeValues, transactionGraphLayout, transactionGraphPage } from '@/utils/transactionGraph';
import CopyValue from '@/components/CopyValue.vue';
import UiIcon from '@/components/UiIcon.vue';

const props = defineProps<{
  idPrefix: string;
  details: TransactionDetails;
  currency: Currency;
  amount: (sats: number, signed?: boolean) => string;
}>();

type Side = 'inputs' | 'outputs';
const pages = ref({ inputs: 0, outputs: 0 });
const selected = ref<string | null>(null);
const hovered = ref<string | null>(null);
const focused = ref<string | null>(null);
const active = computed(() => hovered.value ?? focused.value ?? selected.value);
// Summaries scan the transaction once per data change, not once per page or hover.
const inputSummary = computed(() => summarizeValues(props.details.inputs));
const outputSummary = computed(() => summarizeValues(props.details.outputs));
const inputs = computed(() => transactionGraphPage(props.details.inputs, pages.value.inputs, inputSummary.value));
const outputs = computed(() => transactionGraphPage(props.details.outputs, pages.value.outputs, outputSummary.value));
const graph = computed(() => transactionGraphLayout(
  props.details.inputs.length, props.details.outputs.length, inputs.value.page, outputs.value.page,
));
const sides = computed(() => [
  {
    key: 'inputs' as const, title: 'Inputs', singular: 'Input', page: inputs.value,
    nodes: inputs.value.entries.map(({ item, index }) => ({
      key: `inputs-${index}`, index, value: item.value, coinbase: item.coinbase,
      copyValue: item.address || (!item.coinbase ? item.txid : null),
      copyLabel: item.address ? 'address' : 'transaction ID',
      fallback: item.coinbase ? 'Coinbase · newly created bitcoin' : 'Non-address script',
    })),
  },
  {
    key: 'outputs' as const, title: 'Outputs', singular: 'Output', page: outputs.value,
    nodes: outputs.value.entries.map(({ item, index }) => ({
      key: `outputs-${index}`, index: item.index, value: item.value, coinbase: false,
      copyValue: item.address, copyLabel: 'address', fallback: 'Non-address script',
    })),
  },
]);

function clearHighlight() {
  selected.value = hovered.value = focused.value = null;
}

function valueLabel(value: number | null, coinbase: boolean) {
  return value === null
    ? (coinbase ? 'Not applicable (coinbase)' : 'Unknown value')
    : `${props.amount(value)} ${currencyLabel(props.currency)}`;
}

function focusBranch(key: string) {
  focused.value = key;
  // A stationary pointer must not mask a newly focused keyboard branch.
  hovered.value = null;
}

function leaveFocus(event: FocusEvent) {
  if (!(event.relatedTarget instanceof Node) || !(event.currentTarget as HTMLElement).contains(event.relatedTarget)) {
    focused.value = null;
  }
}

function setPage(side: Side, page: number) {
  pages.value[side] = paginationRange(props.details[side].length, page).page;
}

function nodeTop(side: Side, index: number): string {
  const node = graph.value[side][index];
  if (!node) throw new RangeError(`Missing graph position for ${side} node ${index}`);
  return `${node.y - graph.value.nodeHeight / 2}px`;
}

watch(() => props.details.txid, () => {
  pages.value = { inputs: 0, outputs: 0 };
  clearHighlight();
}, { flush: 'sync' });
watch([() => props.details.inputs.length, () => props.details.outputs.length], () => {
  setPage('inputs', pages.value.inputs);
  setPage('outputs', pages.value.outputs);
  clearHighlight();
}, { flush: 'sync' });
watch([() => pages.value.inputs, () => pages.value.outputs], () => {
  selected.value = null;
  // The group button keeps its DOM identity and keyboard focus when exploring a page.
  if (!hovered.value?.endsWith('-group')) hovered.value = null;
  if (!focused.value?.endsWith('-group')) focused.value = null;
}, { flush: 'sync' });
</script>

<template>
  <section class="transaction-graph" aria-label="Transaction structure" @keydown.esc.stop="clearHighlight">
    <div class="graph-canvas" :class="{ 'has-active-branch': active !== null }" :style="{ '--graph-height': `${graph.height}px`, '--node-height': `${graph.nodeHeight}px`, '--center-y': `${graph.centerY}px`, '--header-height': `${graph.headerHeight}px` }">
      <svg class="graph-edges" :viewBox="`0 0 ${graph.width} ${graph.height}`" preserveAspectRatio="none" aria-hidden="true" focusable="false">
        <template v-for="side in sides" :key="side.key">
          <path v-for="(node, index) in graph[side.key]" :key="index" :d="node.path" :data-side="side.key"
            :class="{ 'edge-active': active === (side.nodes[index]?.key ?? `${side.key}-group`) }" />
        </template>
      </svg>
      <template v-for="side in sides" :key="side.key">
      <section class="graph-side" :data-side="side.key" :aria-labelledby="`${idPrefix}-graph-${side.key}-heading`">
        <header class="graph-side-header">
          <h5 :id="`${idPrefix}-graph-${side.key}-heading`" class="text-sm font-medium">{{ side.title }} ({{ side.page.total }})</h5>
          <div class="graph-side-pager">
            <p :id="`${idPrefix}-graph-${side.key}-count`" role="status" aria-live="polite" aria-atomic="true" class="text-xs text-slate-400">{{ side.page.total ? side.page.start + 1 : 0 }}–{{ side.page.end }} of {{ side.page.total }} {{ side.key }} · Page {{ side.page.page + 1 }} / {{ side.page.pageCount }}</p>
            <nav v-if="side.page.pageCount > 1" :aria-labelledby="`${idPrefix}-graph-${side.key}-heading ${idPrefix}-graph-${side.key}-nav-label`" class="flex gap-1.5">
              <span :id="`${idPrefix}-graph-${side.key}-nav-label`" class="sr-only">graph pagination</span>
              <button type="button" class="graph-page-button" :disabled="side.page.page === 0" :aria-label="`Previous ${side.key} page`" :aria-controls="`${idPrefix}-graph-${side.key}-nodes`" :aria-describedby="`${idPrefix}-graph-${side.key}-count`" @click="setPage(side.key, side.page.page - 1)"><UiIcon name="chevron-left" /></button>
              <button type="button" class="graph-page-button" :disabled="side.page.page + 1 === side.page.pageCount" :aria-label="`Next ${side.key} page`" :aria-controls="`${idPrefix}-graph-${side.key}-nodes`" :aria-describedby="`${idPrefix}-graph-${side.key}-count`" @click="setPage(side.key, side.page.page + 1)"><UiIcon name="chevron-right" /></button>
            </nav>
          </div>
        </header>
        <ul :id="`${idPrefix}-graph-${side.key}-nodes`" class="graph-nodes">
          <li v-for="(node, index) in side.nodes" :key="node.key" class="graph-node" :class="{ 'node-active': active === node.key, 'node-pinned': selected === node.key }"
            :style="{ '--node-top': nodeTop(side.key, index) }"
            @mouseenter="hovered = node.key" @mouseleave="hovered = null" @focusin="focusBranch(node.key)" @focusout="leaveFocus">
            <button type="button" class="branch-button" :aria-pressed="selected === node.key" :aria-label="`Pin ${side.singular.toLowerCase()} #${node.index} branch: ${valueLabel(node.value, node.coinbase)}`" @click="selected = selected === node.key ? null : node.key">
              <span class="shrink-0">#{{ node.index }}</span>
              <span class="min-w-0 truncate text-xs font-medium tabular-nums text-slate-200">{{ valueLabel(node.value, node.coinbase) }}</span>
            </button>
            <CopyValue v-if="node.copyValue" :value="node.copyValue" :display="shortId(node.copyValue)" :label="node.copyLabel" class="ml-auto min-w-0 font-mono text-xs text-slate-300" />
            <p v-else class="ml-auto min-w-0 truncate text-xs text-slate-400">{{ node.fallback }}</p>
          </li>
          <li v-if="side.page.group" :key="`${side.key}-group`" class="graph-node graph-group" :class="{ 'node-active': active === `${side.key}-group` }"
            :style="{ '--node-top': nodeTop(side.key, side.nodes.length) }"
            @mouseenter="hovered = `${side.key}-group`" @mouseleave="hovered = null" @focusin="focusBranch(`${side.key}-group`)" @focusout="leaveFocus">
            <button type="button" class="branch-button" :aria-controls="`${idPrefix}-graph-${side.key}-nodes`" :aria-describedby="`${idPrefix}-graph-${side.key}-count ${idPrefix}-graph-${side.key}-group-sum`"
              :aria-label="`Explore ${side.page.group.count} remaining ${side.key}: page ${side.page.group.nextPage + 1} of ${side.page.pageCount}`" @click="setPage(side.key, side.page.group.nextPage)">
              <span class="shrink-0">+{{ side.page.group.count }} remaining {{ side.key }}</span><span aria-hidden="true">→</span>
            </button>
            <p :id="`${idPrefix}-graph-${side.key}-group-sum`" class="ml-auto min-w-0 truncate text-xs tabular-nums text-slate-300">
              <template v-if="side.page.group.total !== null">Total: {{ amount(side.page.group.total) }} {{ currencyLabel(currency) }}</template>
              <template v-else>Known: {{ amount(side.page.group.knownTotal) }} {{ currencyLabel(currency) }} · {{ side.page.group.unknownCount }} unknown</template>
            </p>
          </li>
        </ul>
        <p v-if="!side.page.total" class="mt-2 text-xs text-slate-500">No {{ side.key }}.</p>
      </section>
      <div v-if="side.key === 'inputs'" class="graph-transaction" :class="{ 'transaction-active': active !== null }">
        <span aria-hidden="true" class="text-xl text-accent">₿</span>
        <span class="text-xs font-medium">Transaction</span>
        <CopyValue :value="details.txid" :display="`${details.txid.slice(0, 4)}…${details.txid.slice(-4)}`" label="transaction ID" class="break-all font-mono text-xs text-slate-300" />
      </div>
      </template>
    </div>
    <p class="mt-2 text-xs leading-relaxed text-slate-500">Totals above cover the entire transaction, not only this page. Except for coinbase, network fees are the difference between total inputs and outputs when all values are known.</p>
  </section>
</template>

<style scoped>
.transaction-graph { container-type: inline-size; min-width: 0; }
.graph-canvas { display: flex; flex-direction: column; gap: 1rem; border: 1px solid #363330; border-radius: 1rem; padding: .75rem; background: rgb(11 10 9 / 60%); }
.graph-edges { display: none; }
.graph-side { min-width: 0; --branch-color: #38bdf8; }
.graph-side[data-side="inputs"] { order: 0; }
.graph-side[data-side="outputs"] { order: 2; --branch-color: #34d399; }
.graph-side-header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .25rem .75rem; color: var(--branch-color); }
.graph-side-pager { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem; }
.graph-nodes { display: grid; gap: .5rem; margin-top: .75rem; }
.graph-node { min-width: 0; display: flex; align-items: center; gap: .5rem; border: 1px solid #363330; border-left: 3px solid var(--branch-color); border-radius: .75rem; padding: .375rem .625rem; background: linear-gradient(180deg, #1a1918, #131211); overflow-wrap: anywhere; transition: border-color .15s ease, background-color .15s ease; }
.graph-node:hover { border-color: color-mix(in srgb, var(--branch-color) 40%, #363330); }
.graph-node.node-active { border-color: var(--branch-color); outline: 2px solid var(--branch-color); outline-offset: 1px; background: #242220; }
.graph-node.node-pinned { border-left-width: 6px; }
.graph-group { border-style: dashed; }
.branch-button { display: flex; min-width: 0; align-items: center; gap: .5rem; min-height: 44px; padding: .125rem .25rem; border-radius: .375rem; text-align: left; font-size: .75rem; color: var(--branch-color); }
.branch-button:hover { background: color-mix(in srgb, var(--branch-color) 10%, transparent); }
.graph-node:not(.graph-group) .branch-button > span:first-child { flex-shrink: 0; padding: .0625rem .375rem; border-radius: .375rem; background: color-mix(in srgb, var(--branch-color) 15%, transparent); font-weight: 600; font-variant-numeric: tabular-nums; }
.graph-transaction { order: 1; display: flex; flex-wrap: wrap; justify-content: center; align-items: center; gap: .5rem; padding: .5rem; border: 1px solid #f7931a; border-radius: 1rem; background: #261e18; min-width: 0; }
.transaction-active { outline: 2px solid #f7931a; outline-offset: 2px; }
/* The same HTML controls serve both layouts: no duplicated IDs, copy buttons or live regions.
   A container query also handles narrow desktop panels without a fixed-width SVG. */
@container (min-width: 56rem) {
  .graph-canvas { position: relative; display: block; height: var(--graph-height); padding: 0; }
  .graph-edges { display: block; position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
  .graph-edges path { fill: none; stroke: #38bdf8; stroke-opacity: .3; stroke-width: 2; vector-effect: non-scaling-stroke; }
  .graph-edges path[data-side="outputs"] { stroke: #34d399; }
  .has-active-branch .graph-edges path:not(.edge-active) { stroke-opacity: .1; }
  .graph-edges path.edge-active { stroke-opacity: 1; stroke-width: 4; }
  .graph-side { position: absolute; top: 0; bottom: 0; width: 34%; }
  .graph-side[data-side="inputs"] { left: 2.5%; }
  .graph-side[data-side="outputs"] { right: 2.5%; }
  .graph-side-header { padding: .75rem; height: var(--header-height); align-content: center; }
  .graph-nodes { display: block; margin: 0; }
  .graph-node { position: absolute; top: var(--node-top); width: 100%; height: var(--node-height); }
  .graph-transaction { position: absolute; top: var(--center-y); left: 44%; width: 12%; transform: translateY(-50%); flex-direction: column; gap: .25rem; }
}
</style>