<script setup lang="ts">
import { computed, ref, useTemplateRef } from 'vue';
import { walletApi } from '../services/walletApi';
import type { ReceiveAddress } from '../types/wallet';
import { useClipboard } from '../composables/useClipboard';
import AddressCheckForm from './AddressCheckForm.vue';
import UiIcon from './UiIcon.vue';

const props = defineProps<{ receive: ReceiveAddress }>();
const emit = defineEmits<{ enlarge: [address: ReceiveAddress, trigger: HTMLButtonElement] }>();
const qrUrl = computed(() => walletApi.qrAtUrl(props.receive.index));
const { copied, error, copy } = useClipboard();

function enlarge(event: MouseEvent): void {
  emit('enlarge', { ...props.receive }, event.currentTarget as HTMLButtonElement);
}

const tabButtons = useTemplateRef<HTMLButtonElement[]>('tabButtons');
const tabs = [
  { id: 'receive', label: 'Receive', icon: 'qr-code' },
  { id: 'check', label: 'Check address', icon: 'search' },
] as const;
const activeTab = ref<(typeof tabs)[number]['id']>('receive');

function navigateTabs(event: KeyboardEvent, index: number): void {
  let nextIndex: number;
  switch (event.key) {
    case 'ArrowRight': nextIndex = (index + 1) % tabs.length; break;
    case 'ArrowLeft': nextIndex = (index + tabs.length - 1) % tabs.length; break;
    case 'Home': nextIndex = 0; break;
    case 'End': nextIndex = tabs.length - 1; break;
    default: return;
  }
  const nextTab = tabs[nextIndex];
  if (!nextTab) return;
  event.preventDefault();
  activeTab.value = nextTab.id;
  tabButtons.value?.[nextIndex]?.focus();
}

</script>

<template>
  <section class="min-w-0" aria-label="Receive Bitcoin">
    <div class="flex flex-wrap items-start gap-3 lg:hidden">
      <button type="button" aria-haspopup="dialog" aria-controls="receive-qr-dialog" class="button-primary inline-flex flex-1 items-center justify-center gap-2 rounded-xl px-5 py-3 text-sm font-semibold" @click="enlarge"><UiIcon name="qr-code" />Receive</button>
      <details class="mobile-address-check min-w-0 flex-1">
        <summary class="button-secondary cursor-pointer rounded-xl px-4 py-3 text-center text-sm font-semibold">Check address</summary>
        <div class="wallet-panel mt-3 p-4"><AddressCheckForm /></div>
      </details>
    </div>
    <div class="wallet-panel hidden h-full p-6 lg:block xl:p-8">
    <div role="tablist" aria-label="Receive Bitcoin" class="mb-5 flex gap-2 border-b border-slate-800">
      <button
        v-for="(tab, index) in tabs"
        :id="`receive-tab-${tab.id}`"
        :key="tab.id"
        ref="tabButtons"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.id"
        :aria-controls="`receive-panel-${tab.id}`"
        :tabindex="activeTab === tab.id ? 0 : -1"
        class="-mb-px inline-flex items-center gap-2 rounded-t-lg border-b-2 px-4 py-2.5 text-sm font-semibold transition focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
        :class="activeTab === tab.id ? 'border-accent bg-accent/5 text-accent' : 'border-transparent text-slate-400 hover:border-slate-600 hover:bg-slate-800/50 hover:text-slate-200'"
        @click="activeTab = tab.id"
        @keydown="navigateTabs($event, index)"
      ><UiIcon :name="tab.icon" />{{ tab.label }}</button>
    </div>

    <div class="grid">
      <div id="receive-panel-receive" role="tabpanel" aria-labelledby="receive-tab-receive" :tabindex="activeTab === 'receive' ? 0 : -1" :aria-hidden="activeTab !== 'receive'" class="[grid-area:1/1] rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent" :class="{ invisible: activeTab !== 'receive' }">
      <p class="mb-4 text-xs text-slate-500">Your next unused address · share it to receive Bitcoin.</p>
      <div class="flex flex-col items-start gap-4 sm:flex-row sm:items-start">
        <button
          type="button"
          aria-haspopup="dialog"
          aria-controls="receive-qr-dialog"
          aria-label="Enlarge receive address QR code"
          @click="enlarge"
          class="shrink-0 cursor-zoom-in rounded-2xl bg-white p-3 shadow-lg transition hover:ring-2 hover:ring-accent"
        >
          <img :src="qrUrl" alt="" class="h-32 w-32" />
        </button>
        <div class="min-w-0 w-full flex-1">
          <button
            type="button"
            :aria-label="`Copy receive address ${receive.address}`"
            @click="copy(receive.address, 'address')"
            class="group my-2 flex w-full items-center gap-3 rounded-lg border border-slate-700 bg-slate-800 p-2.5 text-left transition hover:border-accent/60 hover:bg-slate-800/80"
          >
            <span class="min-w-0 flex-1 select-text break-all font-mono text-sm text-slate-200">{{ receive.address }}</span>
            <span class="inline-flex shrink-0 items-center gap-1.5 text-xs font-semibold" :class="copied ? 'text-emerald-400' : 'text-accent'"><UiIcon :name="copied ? 'check' : 'copy'" />{{ copied ? 'Copied' : 'Copy' }}</span>
          </button>
          <p v-if="error" role="status" class="text-xs text-rose-400">{{ error }}</p>
          <div class="mt-3 flex items-baseline gap-2 rounded-lg border border-slate-700/50 bg-slate-950/40 px-3 py-2 text-xs">
            <span class="shrink-0 text-[0.65rem] font-semibold uppercase tracking-wide text-slate-500">Path</span>
            <span class="min-w-0 flex-1 break-all font-mono text-slate-300">{{ receive.path }}</span>
          </div>
        </div>
      </div>
    </div>

      <div id="receive-panel-check" role="tabpanel" aria-labelledby="receive-tab-check" :tabindex="activeTab === 'check' ? 0 : -1" :aria-hidden="activeTab !== 'check'" class="flex flex-col justify-center [grid-area:1/1] rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent" :class="{ invisible: activeTab !== 'check' }">
        <AddressCheckForm />
      </div>
    </div>
    </div>
  </section>
</template>