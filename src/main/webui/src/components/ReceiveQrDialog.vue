<script setup lang="ts">
import { nextTick, ref } from 'vue';
import { walletApi } from '../services/walletApi';
import type { ReceiveAddress } from '../types/wallet';
import { useModalDialog } from '../composables/useModalDialog';
import CopyButton from './CopyButton.vue';

const dialog = ref<HTMLDialogElement | null>(null);
const receive = ref<(ReceiveAddress & { url: string }) | null>(null);
const { showModal, close, handleClose, closeOnBackdrop, isDisposed } = useModalDialog(dialog);
let opening = false;

async function open(address: ReceiveAddress, trigger: HTMLButtonElement): Promise<void> {
  const element = dialog.value;
  if (isDisposed() || opening || !element || element.open) return;
  opening = true;
  // Freeze the address, derivation index and image URL as one snapshot.
  receive.value = { ...address, url: walletApi.qrAtUrl(address.index) };
  try {
    await nextTick();
    // Native dialog closure restores focus even in browsers that do not focus clicks.
    trigger.focus({ preventScroll: true });
    showModal();
  } finally {
    opening = false;
  }
}

defineExpose({ open });
</script>

<template>
  <dialog
    id="receive-qr-dialog"
    ref="dialog"
    lang="en-US"
    aria-labelledby="receive-qr-title"
    aria-describedby="receive-qr-description"
    class="receive-qr-dialog rounded-2xl border border-slate-700 bg-slate-900 p-4 text-slate-100 shadow-2xl sm:p-6"
    @close="handleClose"
    @click="closeOnBackdrop"
  >
    <div class="mb-3 flex items-start justify-between gap-3">
      <h2 id="receive-qr-title" class="pt-2 text-lg font-semibold">Receive address QR code</h2>
      <button
        type="button"
        autofocus
        @click="close"
        class="button-secondary shrink-0 rounded-lg px-3 text-sm"
      >Close</button>
    </div>
    <p id="receive-qr-description" class="mb-4 text-sm text-slate-400">Scan this QR code to receive Bitcoin at the address below.</p>
    <template v-if="receive">
      <img :src="receive.url" alt="Receive address QR code" class="mx-auto aspect-square w-full max-w-96 rounded-xl bg-white p-3" />
      <p class="mt-4 select-text break-all rounded-lg border border-slate-700 bg-slate-800 p-3 font-mono text-sm">{{ receive.address }}</p>
      <CopyButton :key="receive.address" :value="receive.address" class="mt-3 text-center" />
      <details :key="receive.address" class="mt-3 text-xs text-slate-400">
        <summary class="cursor-pointer py-2">Technical details</summary>
        <p class="mt-1 break-all font-mono">Derivation path: {{ receive.path }}</p>
      </details>
    </template>
  </dialog>
</template>