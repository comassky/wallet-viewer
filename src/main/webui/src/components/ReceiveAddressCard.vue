<script setup lang="ts">
import { computed } from 'vue';
import { walletApi } from '../services/walletApi';
import type { ReceiveAddress } from '../types/wallet';
import CopyValue from './CopyValue.vue';

const props = defineProps<{ receive: ReceiveAddress }>();
const emit = defineEmits<{ enlarge: [address: ReceiveAddress, trigger: HTMLButtonElement] }>();
const qrUrl = computed(() => walletApi.qrAtUrl(props.receive.index));

function enlarge(event: MouseEvent): void {
  emit('enlarge', { ...props.receive }, event.currentTarget as HTMLButtonElement);
}
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
  </section>
</template>