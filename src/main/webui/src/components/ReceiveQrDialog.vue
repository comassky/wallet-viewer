<script setup lang="ts">
import { nextTick, ref } from 'vue';
import { walletApi } from '../services/walletApi';
import type { ReceiveAddress } from '../types/wallet';
import { useModalDialog } from '../composables/useModalDialog';
import UiIcon from './UiIcon.vue';

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
    class="receive-qr-dialog rounded-2xl border border-slate-700 bg-slate-900 p-4 text-slate-100 shadow-2xl sm:p-6"
    @close="handleClose"
    @click="closeOnBackdrop"
  >
    <div class="mb-3 flex items-center justify-between gap-3">
      <h2 id="receive-qr-title" class="text-lg font-semibold">Receive address QR code</h2>
      <button
        type="button"
        autofocus
        @click="close"
        aria-label="Close"
        class="button-secondary inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg"
      >
        <UiIcon name="close" class="h-4 w-4" />
      </button>
    </div>
    <template v-if="receive">
      <img :src="receive.url" alt="Receive address QR code" class="mx-auto aspect-square w-full max-w-96 rounded-xl bg-white p-3" />
    </template>
  </dialog>
</template>