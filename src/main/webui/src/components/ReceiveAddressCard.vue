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
    <h2 class="section-title mb-5">Receive Bitcoin <span class="ml-2 font-normal normal-case tracking-normal text-slate-500">Your next address</span></h2>
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

    <form class="mt-5 border-t border-slate-700/40 pt-5" @submit.prevent="verify">
      <label for="verify-address" class="section-title mb-2 block">Verify an address</label>
      <p class="mb-3 text-xs text-slate-500">Check that an address was derived from this wallet's extended public key.</p>
      <div class="flex flex-col gap-2 sm:flex-row">
        <input
          id="verify-address"
          v-model="query"
          type="text"
          autocomplete="off"
          spellcheck="false"
          placeholder="bc1…"
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
  </section>
</template>