<script setup lang="ts">
import { nextTick, onMounted, onScopeDispose, ref } from 'vue';
import { currencies, currencyLabel, type Currency } from '../currency';
import type { Balance } from '../types/wallet';
import UiIcon from './UiIcon.vue';

defineProps<{
  balance: Balance;
  currency: Currency;
  estimated: boolean;
  amount: (sats: number, signed?: boolean) => string;
}>();
const emit = defineEmits<{ 'update:currency': [value: Currency] }>();
const root = ref<HTMLElement | null>(null);
const trigger = ref<HTMLButtonElement | null>(null);
const choices = ref<HTMLElement | null>(null);
const choosing = ref(false);

async function toggle(): Promise<void> {
  choosing.value = !choosing.value;
  if (choosing.value) {
    await nextTick();
    choices.value?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus();
  }
}

function close(): void {
  choosing.value = false;
  trigger.value?.focus({ preventScroll: true });
}

function select(unit: Currency): void {
  emit('update:currency', unit);
  close();
}

function outside(event: MouseEvent): void {
  if (event.target instanceof Node && !root.value?.contains(event.target)) choosing.value = false;
}
onMounted(() => document.addEventListener('click', outside));
onScopeDispose(() => document.removeEventListener('click', outside));
</script>

<template>
  <section ref="root" class="wallet-panel balance-card relative min-w-0 p-6 sm:p-8" @keydown.esc.stop.prevent="close">
    <div class="mb-6 flex items-center justify-between gap-3">
      <h2 class="section-title flex items-center gap-2"><UiIcon name="coins" class="text-accent" />Total balance</h2>
      <span class="rounded-full border border-accent/20 bg-accent/10 px-2.5 py-1 text-[10px] font-semibold uppercase tracking-widest text-accent">Bitcoin</span>
    </div>
    <button
      ref="trigger"
      type="button"
      class="group flex max-w-full flex-wrap items-baseline gap-x-3 gap-y-1 rounded-lg text-left"
      :aria-label="`Balance ${amount(balance.total)} ${currencyLabel(currency)}. Change display currency`"
      :aria-expanded="choosing"
      aria-controls="balance-currency-choices"
      @click="toggle"
    >
      <span class="break-all text-4xl font-semibold tracking-tight tabular-nums transition group-hover:text-accent sm:text-5xl">{{ estimated ? '≈ ' : '' }}{{ amount(balance.total) }}</span>
      <span class="flex items-center gap-2 text-lg font-medium text-accent">{{ currencyLabel(currency) }} <span class="text-sm text-slate-500" aria-hidden="true">⌄</span></span>
    </button>
    <p class="mt-2 text-xs text-slate-500">Click your balance to change currency · saved on this device</p>
    <div v-if="choosing" id="balance-currency-choices" ref="choices" role="group" aria-label="Display currency" class="mt-4 flex w-fit flex-wrap gap-1 rounded-2xl border border-slate-600/50 bg-slate-950/90 p-1.5 shadow-xl">
      <button v-for="unit in currencies" :key="unit" type="button" :aria-pressed="currency === unit" @click="select(unit)"
        class="min-w-14 rounded-xl px-4 text-sm font-semibold transition"
        :class="currency === unit ? 'bg-accent text-slate-950' : 'text-slate-300 hover:bg-slate-800'"
      >{{ currencyLabel(unit) }}</button>
    </div>
    <dl class="mt-7 grid gap-4 border-t border-slate-700/40 pt-5 sm:grid-cols-2">
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