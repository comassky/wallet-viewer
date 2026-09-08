<script setup lang="ts">
import type { WalletConnection } from '../services/walletStream';
import type { WalletStatus } from '../types/wallet';
import { usePrivacy } from '../composables/usePrivacy';
import WalletLiveStatus from './WalletLiveStatus.vue';
import UiIcon from './UiIcon.vue';

defineProps<{
  connection: WalletConnection;
  status: WalletStatus;
  message: string | null;
  notifySupported: boolean;
  notifyEnabled: boolean;
}>();
defineEmits<{ retry: []; 'toggle-notify': [] }>();
const { hidden, toggle: togglePrivacy } = usePrivacy();
const pillClass = 'inline-flex h-9 w-9 items-center justify-center rounded-full border border-slate-700/60 text-slate-400 transition hover:border-accent/50 hover:text-accent';
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
      <button type="button" :class="pillClass" :aria-pressed="hidden" :aria-label="hidden ? 'Show amounts' : 'Hide amounts'" @click="togglePrivacy">
        <UiIcon :name="hidden ? 'eye-off' : 'eye'" />
      </button>
      <button v-if="notifySupported" type="button" :class="[pillClass, notifyEnabled ? 'border-accent/50 text-accent' : '']" :aria-pressed="notifyEnabled" aria-label="Toggle incoming payment notifications" @click="$emit('toggle-notify')">
        <UiIcon name="bell" />
      </button>
      <span class="inline-flex items-center gap-2 rounded-full border border-slate-700/60 px-3 py-2 text-[10px] uppercase tracking-widest text-slate-400"><UiIcon name="shield" />Read-only wallet</span>
    </div>
  </header>
</template>