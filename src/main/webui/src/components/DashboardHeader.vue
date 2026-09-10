<script setup lang="ts">
import type { WalletConnection } from '../services/walletStream';
import type { WalletStatus } from '../types/wallet';
import { usePrivacy } from '../composables/usePrivacy';
import WalletLiveStatus from './WalletLiveStatus.vue';
import FeeGauge from './FeeGauge.vue';
import UiIcon from './UiIcon.vue';

defineProps<{
  connection: WalletConnection;
  status: WalletStatus;
  message: string | null;
}>();
defineEmits<{ retry: [] }>();
const { hidden, toggle: togglePrivacy } = usePrivacy();
const pillClass = 'inline-flex h-9 w-9 items-center justify-center rounded-full border border-slate-700/60 text-slate-400 transition hover:border-accent/50 hover:text-accent';
</script>

<template>
  <header class="relative mb-4 flex flex-wrap items-center justify-between gap-3 border-b border-slate-700/50 pb-4 sm:mb-8 sm:gap-5 sm:pb-6">
    <div class="flex min-w-0 items-center gap-2.5 pr-12 sm:gap-3.5 sm:pr-0">
      <img src="/logo.png" alt="" width="48" height="48" class="h-10 w-10 shrink-0 drop-shadow-[0_0_16px_#f7931a30] sm:h-12 sm:w-12" />
      <div>
        <h1 class="text-xl font-semibold sm:text-2xl">Wallet <span class="font-normal text-accent">Viewer</span></h1>
        <p class="mt-1 text-[10px] text-slate-500 sm:uppercase sm:tracking-[0.2em]">Bitcoin · self-hosted · watch-only</p>
      </div>
    </div>
    <div class="flex w-full flex-wrap items-center gap-2 sm:w-auto sm:gap-3">
      <WalletLiveStatus :connection="connection" :status="status" :message="message" @retry="$emit('retry')" />
      <FeeGauge />
      <button type="button" class="max-sm:absolute max-sm:right-0 max-sm:top-0" :class="pillClass" :aria-pressed="hidden" :aria-label="hidden ? 'Show amounts' : 'Hide amounts'" @click="togglePrivacy">
        <UiIcon :name="hidden ? 'eye-off' : 'eye'" />
      </button>
      <span class="hidden items-center gap-2 rounded-full border border-slate-700/60 px-3 py-2 text-[10px] uppercase tracking-widest text-slate-400 sm:inline-flex"><UiIcon name="shield" />Read-only wallet</span>
    </div>
  </header>
</template>