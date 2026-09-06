<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { walletApi } from '../services/walletApi';
import type { AddressCheck, ReceiveAddress } from '../types/wallet';
import CopyValue from './CopyValue.vue';

const props = defineProps<{ receive: ReceiveAddress }>();
const emit = defineEmits<{ enlarge: [address: ReceiveAddress, trigger: HTMLButtonElement] }>();
const qrUrl = computed(() => walletApi.qrAtUrl(props.receive.index));

function enlarge(event: MouseEvent): void {
  emit('enlarge', { ...props.receive }, event.currentTarget as HTMLButtonElement);
}

const tabButtons = ref<HTMLButtonElement[]>([]);
const tabs = [
  { id: 'receive', label: 'Receive' },
  { id: 'check', label: 'Check address' },
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
  event.preventDefault();
  activeTab.value = tabs[nextIndex].id;
  tabButtons.value[nextIndex]?.focus();
}

const query = ref('');
const checking = ref(false);
const result = ref<AddressCheck | null>(null);
const error = ref<string | null>(null);
let controller: AbortController | null = null;

// A new input invalidates the previous verdict.
watch(query, () => { result.value = null; error.value = null; });

async function verify(): Promise<void> {
  const value = query.value.trim();
  controller?.abort();
  result.value = null;
  error.value = null;
  if (!value) return;
  const request = new AbortController();
  controller = request;
  checking.value = true;
  try {
    const check = await walletApi.verifyAddress(value, { signal: request.signal });
    if (controller !== request) return;
    result.value = check;
  } catch (failure) {
    if (controller === request) error.value = failure instanceof Error ? failure.message : 'Verification failed.';
  } finally {
    if (controller === request) { checking.value = false; controller = null; }
  }
}

onScopeDispose(() => controller?.abort());
</script>

<template>
  <section class="wallet-panel min-w-0 p-6 sm:p-8">
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
        class="-mb-px rounded-t-lg border-b-2 px-4 py-2.5 text-sm font-semibold transition focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
        :class="activeTab === tab.id ? 'border-accent bg-accent/5 text-accent' : 'border-transparent text-slate-400 hover:border-slate-600 hover:bg-slate-800/50 hover:text-slate-200'"
        @click="activeTab = tab.id"
        @keydown="navigateTabs($event, index)"
      >{{ tab.label }}</button>
    </div>

    <div class="grid">
      <div id="receive-panel-receive" role="tabpanel" aria-labelledby="receive-tab-receive" :tabindex="activeTab === 'receive' ? 0 : -1" :aria-hidden="activeTab !== 'receive'" class="[grid-area:1/1] rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent" :class="{ invisible: activeTab !== 'receive' }">
      <p class="mb-4 text-xs text-slate-500">Your next unused address · share it to receive Bitcoin.</p>
      <div class="flex flex-col items-start gap-4 sm:flex-row sm:items-center">
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
          <div class="my-2 select-text break-all rounded-lg border border-slate-700 bg-slate-800 p-2.5 font-mono text-sm">
            <CopyValue :value="receive.address" label="address" status-right />
          </div>
          <div class="break-all font-mono text-xs text-slate-400">{{ receive.path }}</div>
        </div>
      </div>
    </div>

      <div id="receive-panel-check" role="tabpanel" aria-labelledby="receive-tab-check" :tabindex="activeTab === 'check' ? 0 : -1" :aria-hidden="activeTab !== 'check'" class="flex flex-col justify-center [grid-area:1/1] rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent" :class="{ invisible: activeTab !== 'check' }">
      <form @submit.prevent="verify">
        <p class="mb-3 text-xs text-slate-500">Check that an address was derived from this wallet's extended public key.</p>
        <div class="flex flex-col gap-2 sm:flex-row">
          <input
            id="verify-address"
            v-model="query"
            type="text"
            autocomplete="off"
            spellcheck="false"
            placeholder="bc1…"
            aria-label="Address to verify"
            class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-800 px-3 py-2 font-mono text-sm text-slate-100 placeholder:text-slate-500 focus:border-accent focus:outline-none"
          />
          <button type="submit" :disabled="checking || !query.trim()" class="button-secondary shrink-0 rounded-lg px-4 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-50">{{ checking ? 'Checking…' : 'Verify' }}</button>
        </div>
        <p v-if="error" role="alert" class="mt-3 text-xs text-rose-400">{{ error }}</p>
        <p v-else-if="result?.belongs" role="status" class="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-emerald-400">
          <span>✓ Belongs to this wallet</span>
          <span class="text-slate-400">· {{ result.chain === 0 ? 'receive' : 'change' }} address #{{ result.index }}</span>
          <span class="break-all font-mono text-slate-500">{{ result.path }}</span>
        </p>
        <p v-else-if="result" role="status" class="mt-3 text-xs text-amber-400">Not derived from this wallet (checked the first {{ result.checked }} receive and change addresses).</p>
      </form>
      </div>
    </div>
  </section>
</template>