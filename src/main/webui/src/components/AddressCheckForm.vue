<script setup lang="ts">
import { onScopeDispose, ref, useId, useTemplateRef, watch } from 'vue';
import { walletApi } from '@/services/walletApi';
import type { AddressCheck } from '@/types/wallet';
import UiIcon from '@/components/UiIcon.vue';

const inputId = useId();
const addressInput = useTemplateRef<HTMLInputElement>('addressInput');
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

function clearAddress(): void {
  query.value = '';
  addressInput.value?.focus();
}
</script>

<template>
  <form @submit.prevent="verify" :aria-busy="checking">
    <label :for="inputId" class="mb-2 flex items-center gap-2 text-sm font-medium text-slate-200"><UiIcon name="search" class="text-slate-500" />Bitcoin address</label>
    <div class="flex flex-col gap-2 sm:flex-row">
      <div class="relative min-w-0 flex-1">
        <input ref="addressInput" :id="inputId" v-model="query" type="text" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="bc1…" class="min-h-12 w-full min-w-0 rounded-lg border border-slate-700 bg-slate-950/50 py-3 pl-3 pr-12 font-mono text-base text-slate-100 transition placeholder:text-slate-600 hover:border-slate-500 focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/15 sm:text-sm" />
        <button v-if="query" type="button" aria-label="Clear address" title="Clear address" class="absolute inset-y-0 right-0 flex w-11 items-center justify-center rounded-r-lg text-slate-500 transition hover:text-slate-100 focus-visible:text-accent" @click="clearAddress"><UiIcon name="close" /></button>
      </div>
      <button type="submit" :disabled="checking || !query.trim()" class="button-primary inline-flex min-h-12 min-w-32 shrink-0 items-center justify-center gap-2 rounded-lg px-4 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-40">
        <span v-if="checking" aria-hidden="true" class="h-4 w-4 animate-spin rounded-full border-2 border-slate-600 border-t-accent motion-reduce:animate-none" />
        <UiIcon v-else name="search" />
        {{ checking ? 'Checking…' : 'Verify' }}
      </button>
    </div>
    <div class="mt-5 min-h-28 border-t border-slate-700/60 pt-4">
      <div v-if="error" role="alert" class="flex items-start gap-3">
        <UiIcon name="alert" class="mt-0.5 h-5 w-5 text-rose-400" />
        <div class="min-w-0"><p class="text-sm font-semibold text-rose-400">Verification unavailable</p><p class="mt-1 break-words text-xs leading-relaxed text-slate-400">{{ error }}</p></div>
      </div>
      <div v-else-if="result?.belongs" role="status" class="flex items-start gap-3">
        <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-emerald-400/10 text-emerald-400"><UiIcon name="shield" class="h-5 w-5" /></span>
        <div class="min-w-0 flex-1">
          <p class="text-sm font-semibold text-emerald-400">Belongs to this wallet</p>
          <dl class="mt-2 flex flex-wrap gap-x-5 gap-y-2 text-xs">
            <div class="flex items-baseline gap-2"><dt class="text-slate-500">Chain</dt><dd class="text-slate-200">{{ result.chain === 0 ? 'Receive' : 'Change' }}</dd></div>
            <div class="flex items-baseline gap-2"><dt class="text-slate-500">Index</dt><dd class="font-mono tabular-nums text-slate-200">#{{ result.index }}</dd></div>
            <div class="flex w-full min-w-0 items-baseline gap-2"><dt class="shrink-0 text-slate-500">Path</dt><dd class="min-w-0 break-all font-mono text-slate-300">{{ result.path }}</dd></div>
          </dl>
        </div>
      </div>
      <div v-else-if="result" role="status" class="flex items-start gap-3">
        <UiIcon name="alert" class="mt-0.5 h-5 w-5 text-amber-400" />
        <div class="min-w-0"><p class="text-sm font-semibold text-amber-400">Not found in checked addresses</p><p class="mt-1 text-xs leading-relaxed text-slate-400">Not found in the first {{ result.checked }} receive and change addresses checked. Addresses beyond this range have not been checked.</p></div>
      </div>
      <p v-else role="status" class="flex items-center gap-2 py-2 text-xs text-slate-500"><UiIcon name="shield" />{{ checking ? 'Verification in progress…' : 'No address verified' }}</p>
    </div>
  </form>
</template>