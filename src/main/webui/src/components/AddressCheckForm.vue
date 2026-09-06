<script setup lang="ts">
import { onScopeDispose, ref, useId, watch } from 'vue';
import { walletApi } from '../services/walletApi';
import type { AddressCheck } from '../types/wallet';
import UiIcon from './UiIcon.vue';

const inputId = useId();
const query = ref('');
const checking = ref(false);
const result = ref<AddressCheck | null>(null);
const error = ref<string | null>(null);
let controller: AbortController | null = null;

// Editing the address must also invalidate a verdict that is still in flight.
watch(query, () => {
  controller?.abort();
  controller = null;
  checking.value = false;
  result.value = null;
  error.value = null;
}, { flush: 'sync' });

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
    if (controller === request) result.value = check;
  } catch (failure) {
    if (controller === request) error.value = failure instanceof Error ? failure.message : 'Verification failed.';
  } finally {
    if (controller === request) { checking.value = false; controller = null; }
  }
}

onScopeDispose(() => { controller?.abort(); controller = null; });
</script>

<template>
  <form @submit.prevent="verify" :aria-busy="checking">
    <p :id="`${inputId}-hint`" class="mb-3 text-xs text-slate-400">Check whether an address belongs to this wallet.</p>
    <label :for="inputId" class="mb-2 block text-sm font-medium text-slate-300">Address to verify</label>
    <div class="flex flex-col gap-2 sm:flex-row">
      <input :id="inputId" v-model="query" type="text" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="bc1…" :aria-describedby="`${inputId}-hint`" class="min-h-11 min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-800 px-3 py-2 font-mono text-sm text-slate-100 placeholder:text-slate-500" />
      <button type="submit" :disabled="checking || !query.trim()" class="button-secondary inline-flex shrink-0 items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-50">
        <span v-if="checking" aria-hidden="true" class="h-4 w-4 animate-spin rounded-full border-2 border-slate-600 border-t-accent motion-reduce:animate-none" />
        {{ checking ? 'Checking…' : 'Verify' }}
      </button>
    </div>
    <p v-if="error" role="alert" class="mt-3 flex items-start gap-2 text-xs text-rose-400"><UiIcon name="alert" class="mt-0.5 shrink-0" /><span>{{ error }}</span></p>
    <p v-else-if="result?.belongs" role="status" class="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-emerald-400">
      <UiIcon name="circle-check" /><span>Belongs to this wallet</span>
      <span class="text-slate-400">· {{ result.chain === 0 ? 'receive' : 'change' }} address #{{ result.index }}</span>
      <span class="break-all font-mono text-slate-400">{{ result.path }}</span>
    </p>
    <p v-else-if="result" role="status" class="mt-3 flex items-start gap-2 text-xs text-amber-400"><UiIcon name="alert" class="mt-0.5 shrink-0" /><span>Not found in the first {{ result.checked }} receive and change addresses checked. Addresses beyond this range have not been checked.</span></p>
  </form>
</template>