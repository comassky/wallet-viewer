<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue';
import { useFees } from '../composables/useFees';
import { formatDate } from '../utils/format';
import UiIcon from './UiIcon.vue';

const { fees, loading, error } = useFees();
const root = ref<HTMLElement | null>(null);
const trigger = ref<HTMLButtonElement | null>(null);
const opened = ref(false);
const panelLeft = ref(0);

const tiers = [
  { key: 'fastest', label: 'Fast', hint: '~10 min', dot: 'bg-emerald-400', value: 'text-emerald-400' },
  { key: 'halfHour', label: 'Medium', hint: '~30 min', dot: 'bg-accent', value: 'text-accent' },
  { key: 'hour', label: 'Slow', hint: '~1 hour', dot: 'bg-slate-400', value: 'text-slate-200' },
] as const;

function show() {
  if (opened.value) return;
  const left = root.value?.getBoundingClientRect().left ?? 16;
  const width = Math.min(320, window.innerWidth - 32);
  panelLeft.value = Math.max(16 - left, Math.min(0, window.innerWidth - 16 - left - width));
  opened.value = true;
}
function leave() { if (!root.value?.contains(document.activeElement)) opened.value = false; }
function blur(event: FocusEvent) {
  if (!(event.relatedTarget instanceof Node)) return;
  if (!root.value?.contains(event.relatedTarget)) opened.value = false;
}
function outside(event: PointerEvent) {
  if (event.target instanceof Node && !root.value?.contains(event.target)) opened.value = false;
}
function dismiss() { opened.value = false; trigger.value?.focus(); }
onMounted(() => document.addEventListener('pointerdown', outside));
onScopeDispose(() => document.removeEventListener('pointerdown', outside));
</script>

<template>
  <div ref="root" class="relative" @mouseenter="show" @mouseleave="leave" @focusout="blur" @keydown.esc.stop.prevent="dismiss">
    <button
      ref="trigger" type="button" aria-controls="network-fees-info" :aria-expanded="opened" aria-label="Network fees"
      class="inline-flex items-center gap-2 rounded-full border border-slate-700/60 px-3 py-2 text-xs font-medium text-slate-300 transition hover:border-accent/40 hover:text-accent"
      @focus="show" @click="show"
    >
      <UiIcon name="gauge" />
      <span class="tabular-nums">{{ fees ? fees.halfHour : '—' }}</span>
      <span class="text-slate-500">sat/vB</span>
    </button>
    <div v-if="opened" id="network-fees-info" role="region" aria-labelledby="network-fees-title" :style="{ left: `${panelLeft}px` }" class="absolute top-full z-50 w-[min(20rem,calc(100vw-2rem))] pt-2">
      <div class="rounded-2xl border border-accent/25 bg-slate-900 p-4 shadow-2xl">
        <h2 id="network-fees-title" class="mb-3 flex items-center gap-2 text-sm font-semibold"><UiIcon name="gauge" class="text-accent" />Network fees</h2>
        <dl v-if="fees" class="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-4 gap-y-2 text-xs">
          <template v-for="tier in tiers" :key="tier.key">
            <dt class="flex items-center gap-2 text-slate-400"><span class="h-1.5 w-1.5 rounded-full" :class="tier.dot" aria-hidden="true" />{{ tier.label }} <span class="text-slate-600">{{ tier.hint }}</span></dt>
            <dd class="text-right font-semibold tabular-nums" :class="tier.value">{{ fees[tier.key] }} <span class="font-normal text-slate-500">sat/vB</span></dd>
          </template>
          <dt class="text-slate-500">Economy</dt><dd class="text-right tabular-nums text-slate-300">{{ fees.economy }} <span class="text-slate-500">sat/vB</span></dd>
          <dt class="text-slate-500">Minimum</dt><dd class="text-right tabular-nums text-slate-300">{{ fees.minimum }} <span class="text-slate-500">sat/vB</span></dd>
        </dl>
        <p v-else-if="error" class="text-xs text-amber-300">Fee estimates unavailable.</p>
        <p v-else class="text-xs text-slate-400">{{ loading ? 'Loading network fees…' : 'Waiting for fee estimates…' }}</p>
        <p v-if="fees" class="mt-3 border-t border-slate-800 pt-3 text-[11px] text-slate-500">Updated {{ formatDate(fees.timestamp) }} · mempool.space (via backend)</p>
      </div>
    </div>
  </div>
</template>
