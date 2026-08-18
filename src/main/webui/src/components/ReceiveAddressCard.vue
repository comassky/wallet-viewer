<script setup lang="ts">
import { computed, watch } from 'vue';
import { useClipboard } from '../composables/useClipboard';
import { walletApi } from '../services/walletApi';
import type { ReceiveAddress } from '../types/wallet';

const props = defineProps<{ receive: ReceiveAddress }>();
const emit = defineEmits<{ enlarge: [address: ReceiveAddress, trigger: HTMLButtonElement] }>();
const { copied, error, copy, reset } = useClipboard();
const qrUrl = computed(() => walletApi.qrAtUrl(props.receive.index));

watch(() => props.receive.address, reset);

function enlarge(event: MouseEvent): void {
  emit('enlarge', { ...props.receive }, event.currentTarget as HTMLButtonElement);
}
</script>

<template>
  <section class="min-w-0 rounded-xl border border-slate-800 bg-slate-900 p-4 sm:p-5">
    <h2 class="mb-3.5 text-xs font-medium uppercase tracking-wider text-slate-400">Receive address</h2>
    <div class="flex flex-col items-start gap-4 sm:flex-row sm:items-center">
      <button
        type="button"
        aria-haspopup="dialog"
        aria-controls="receive-qr-dialog"
        aria-label="Enlarge receive address QR code"
        @click="enlarge"
        class="shrink-0 cursor-zoom-in rounded-lg bg-white p-2 transition hover:ring-2 hover:ring-accent"
      >
        <img :src="qrUrl" alt="" class="h-32 w-32" />
      </button>
      <div class="min-w-0 w-full flex-1">
        <div class="my-2 select-text break-all rounded-lg border border-slate-700 bg-slate-800 p-2.5 font-mono text-sm">
          {{ receive.address }}
        </div>
        <div class="break-all font-mono text-xs text-slate-400">{{ receive.path }}</div>
        <button
          type="button"
          @click="copy(receive.address)"
          class="mt-2 rounded-md border border-slate-700 bg-slate-800 px-2.5 py-1 text-xs transition hover:border-accent"
        >{{ copied ? '✓ Copied' : 'Copy address' }}</button>
        <p role="status" class="mt-2 text-xs" :class="error ? 'text-rose-400' : 'text-slate-400'">
          {{ error || (copied ? 'Address copied.' : '') }}
        </p>
      </div>
    </div>
  </section>
</template>