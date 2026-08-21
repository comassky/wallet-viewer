<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue';
import { walletApi } from '../services/walletApi';
import type { ReceiveAddress } from '../types/wallet';
import CopyAddressButton from './CopyAddressButton.vue';
import CopyValue from './CopyValue.vue';

const dialog = ref<HTMLDialogElement | null>(null);
const receive = ref<(ReceiveAddress & { url: string }) | null>(null);
const copyButton = ref<InstanceType<typeof CopyAddressButton> | null>(null);
let savedOverflow: { value: string; priority: string } | null = null;
let disposed = false;
let opening = false;

async function open(address: ReceiveAddress, trigger: HTMLButtonElement): Promise<void> {
  const element = dialog.value;
  if (disposed || opening || !element || element.open) return;
  opening = true;
  // Freeze the address, derivation index and image URL as one snapshot.
  receive.value = { ...address, url: walletApi.qrAtUrl(address.index) };
  copyButton.value?.reset();
  try {
    await nextTick();
    if (disposed || !element.isConnected || element.open) return;
    // Native dialog closure restores focus even in browsers that do not focus clicks.
    trigger.focus({ preventScroll: true });
    element.showModal();
    const style = document.documentElement.style;
    savedOverflow ??= { value: style.getPropertyValue('overflow'), priority: style.getPropertyPriority('overflow') };
    style.setProperty('overflow', 'hidden');
  } finally {
    opening = false;
  }
}

function restoreScrolling(): void {
  if (!savedOverflow) return;
  const style = document.documentElement.style;
  if (savedOverflow.value) style.setProperty('overflow', savedOverflow.value, savedOverflow.priority);
  else style.removeProperty('overflow');
  savedOverflow = null;
}

function onClose(): void {
  if (dialog.value?.open) return;
  restoreScrolling();
  copyButton.value?.reset();
}

function closeOnBackdrop(event: MouseEvent): void {
  const element = dialog.value;
  if (!element || event.target !== element) return;
  const bounds = element.getBoundingClientRect();
  if (event.clientX < bounds.left || event.clientX > bounds.right ||
      event.clientY < bounds.top || event.clientY > bounds.bottom) {
    element.close();
  }
}

onBeforeUnmount(() => {
  disposed = true;
  dialog.value?.close();
  restoreScrolling();
});

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
    @close="onClose"
    @click="closeOnBackdrop"
  >
    <div class="mb-3 flex items-start justify-between gap-3">
      <h2 id="receive-qr-title" class="pt-2 text-lg font-semibold">Receive address QR code</h2>
      <button
        type="button"
        autofocus
        @click="dialog?.close()"
        class="button-secondary shrink-0 rounded-lg px-3 text-sm"
      >Close</button>
    </div>
    <p id="receive-qr-description" class="mb-4 text-sm text-slate-400">Scan this QR code to receive Bitcoin at the address below.</p>
    <template v-if="receive">
      <img :src="receive.url" alt="Receive address QR code" class="mx-auto aspect-square w-full max-w-96 rounded-xl bg-white p-3" />
      <p class="mt-4 select-text break-all rounded-lg border border-slate-700 bg-slate-800 p-3 font-mono text-sm"><CopyValue :value="receive.address" label="address" /></p>
      <p class="mt-2 break-all font-mono text-xs text-slate-400">Path: {{ receive.path }}</p>
      <CopyAddressButton ref="copyButton" :address="receive.address" />
    </template>
  </dialog>
</template>