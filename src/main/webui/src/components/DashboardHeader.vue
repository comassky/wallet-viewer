<script setup lang="ts">
import type { WalletConnection } from '../services/walletStream';
import type { WalletStatus } from '../types/wallet';
import WalletLiveStatus from './WalletLiveStatus.vue';
import UiIcon from './UiIcon.vue';

defineProps<{ loading: boolean; connection: WalletConnection; status: WalletStatus; message: string | null }>();
defineEmits<{ refresh: []; retry: [] }>();
</script>

<template>
  <header class="mb-8 flex flex-wrap items-center justify-between gap-5 border-b border-slate-700/50 pb-6">
    <div class="flex items-center gap-3.5">
      <img src="/logo.png" alt="" width="48" height="48" class="h-12 w-12 shrink-0 drop-shadow-[0_0_16px_#f7931a30]" />
      <div>
        <h1 class="text-xl font-semibold tracking-tight sm:text-2xl">Wallet <span class="font-normal text-accent">Viewer</span></h1>
        <p class="mt-1 text-[10px] uppercase tracking-[0.2em] text-slate-500">Bitcoin · self-hosted · watch-only</p>
      </div>
    </div>
    <div class="flex flex-wrap items-center gap-2 sm:gap-3">
      <WalletLiveStatus :connection="connection" :status="status" :message="message" @retry="$emit('retry')" />
      <span class="inline-flex items-center gap-2 rounded-full border border-slate-700/60 px-3 py-2 text-[10px] uppercase tracking-widest text-slate-400"><UiIcon name="shield" />Read-only wallet</span>
      <button
        type="button"
        @click="$emit('refresh')"
        :disabled="loading"
        title="Replay the wallet cache and refresh fiat quotes (does not rescan the wallet)"
        class="button-secondary inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm disabled:opacity-50"
      ><UiIcon name="refresh" :class="{ 'animate-spin motion-reduce:animate-none': loading }" /> {{ loading ? 'Loading…' : 'Refresh' }}</button>
    </div>
  </header>
</template>