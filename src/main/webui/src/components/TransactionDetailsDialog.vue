<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import { currencyLabel, type Currency } from '../currency';
import type { Transaction } from '../types/wallet';
import { formatDate } from '../utils/format';
import { useTransactionDetails } from '../composables/useTransactionDetails';
import TransactionGraph from './TransactionGraph.vue';
import TransactionBadge from './TransactionBadge.vue';
import ConfirmationStatus from './ConfirmationStatus.vue';

defineProps<{ currency: Currency; amount: (sats: number, signed?: boolean) => string }>();
const dialog = ref<HTMLDialogElement | null>(null);
const transaction = ref<Transaction | null>(null);
const { details, loading, error, load, cancel } = useTransactionDetails();
let savedOverflow: { value: string; priority: string } | null = null;

function open(tx: Transaction, trigger: HTMLButtonElement): void {
  if (!dialog.value || dialog.value.open) return;
  transaction.value = { ...tx };
  trigger.focus({ preventScroll: true });
  dialog.value.showModal();
  const style = document.documentElement.style;
  savedOverflow = { value: style.getPropertyValue('overflow'), priority: style.getPropertyPriority('overflow') };
  style.setProperty('overflow', 'hidden');
  void load(tx.txid);
}

function onClose(): void {
  if (dialog.value?.open) return;
  cancel();
  if (!savedOverflow) return;
  const style = document.documentElement.style;
  if (savedOverflow.value) style.setProperty('overflow', savedOverflow.value, savedOverflow.priority);
  else style.removeProperty('overflow');
  savedOverflow = null;
}

function closeOnBackdrop(event: MouseEvent): void {
  const element = dialog.value;
  if (!element || event.target !== element) return;
  const bounds = element.getBoundingClientRect();
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) element.close();
}

onBeforeUnmount(() => { dialog.value?.close(); onClose(); });
defineExpose({ open });
</script>

<template>
  <dialog ref="dialog" id="transaction-details-dialog" class="transaction-dialog" lang="en-US" aria-labelledby="transaction-details-title" @close="onClose" @click="closeOnBackdrop">
    <div class="sticky top-0 z-10 flex items-center justify-between gap-4 border-b border-slate-700/50 bg-slate-900/95 px-5 py-4 backdrop-blur sm:px-7">
      <div>
        <p class="mb-1 text-[10px] font-semibold uppercase tracking-[0.2em] text-accent">Bitcoin explorer</p>
        <h2 id="transaction-details-title" class="text-lg font-semibold tracking-tight">Transaction details</h2>
      </div>
      <button type="button" autofocus class="button-secondary rounded-xl px-4 text-sm" @click="dialog?.close()">Close <span aria-hidden="true">×</span></button>
    </div>
    <div v-if="transaction" class="space-y-6 p-5 sm:p-7">
      <div>
        <div class="mb-3 flex flex-wrap items-center gap-3">
          <TransactionBadge :type="transaction.type" />
          <ConfirmationStatus :confirmations="transaction.confirmations" />
          <span class="text-xs text-slate-400">{{ formatDate(transaction.timestamp) }}</span>
        </div>
        <p class="select-text break-all font-mono text-xs leading-relaxed text-slate-400">{{ transaction.txid }}</p>
        <p class="mt-1 text-[11px] text-slate-500">Confirmation status captured when opened.</p>
      </div>
      <div v-if="loading" role="status" class="flex flex-col items-center gap-4 py-16 text-sm text-slate-400">
        <span class="h-8 w-8 animate-spin rounded-full border-2 border-slate-700 border-t-accent motion-reduce:animate-none" aria-hidden="true" />
        Loading transaction and previous outputs from Electrum…
      </div>
      <div v-else-if="error" class="rounded-2xl border border-rose-500/20 bg-rose-500/5 p-5">
        <p role="alert" class="break-words text-sm text-rose-300">{{ error }}</p>
        <p class="mt-2 text-xs text-slate-400">Your Electrum server must be able to return this transaction and its previous transactions.</p>
        <button type="button" class="button-secondary mt-4 rounded-xl px-4 text-sm" @click="load(transaction.txid)">Try again</button>
      </div>
      <template v-else-if="details">
        <dl class="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <div class="detail-stat"><dt class="mb-2 text-xs text-slate-400">Total inputs</dt><dd class="break-all text-lg font-medium tabular-nums">{{ details.totalInput === null ? 'Coinbase' : `${amount(details.totalInput)} ${currencyLabel(currency)}` }}</dd></div>
          <div class="detail-stat"><dt class="mb-2 text-xs text-slate-400">Total outputs</dt><dd class="break-all text-lg font-medium tabular-nums">{{ amount(details.totalOutput) }} {{ currencyLabel(currency) }}</dd></div>
          <div class="detail-stat"><dt class="mb-2 text-xs text-slate-400">Network fee</dt><dd class="break-all text-lg font-medium tabular-nums text-accent">{{ details.fee === null ? 'Not applicable' : `${amount(details.fee)} ${currencyLabel(currency)}` }}</dd><dd v-if="details.fee !== null" class="mt-1 text-xs text-slate-500">{{ details.fee.toLocaleString('en-US') }} satoshis</dd></div>
        </dl>
        <TransactionGraph :details="details" :currency="currency" :amount="amount" />
        <div class="grid min-w-0 gap-6 lg:grid-cols-2">
          <section class="min-w-0" aria-labelledby="transaction-inputs-title">
            <h3 id="transaction-inputs-title" class="section-title mb-3 text-sky-400">Inputs <span class="text-slate-500">({{ details.inputs.length }})</span></h3>
            <ol class="space-y-2">
              <li v-for="(input, index) in details.inputs" :key="index" class="detail-stat">
                <div class="mb-2 flex flex-wrap justify-between gap-2 text-sm"><span class="text-slate-500">#{{ index }}</span><b class="font-medium tabular-nums">{{ input.value === null ? 'Newly created bitcoin' : `${amount(input.value)} ${currencyLabel(currency)}` }}</b></div>
                <p class="select-text break-all font-mono text-xs leading-relaxed text-slate-300">{{ input.coinbase ? 'Coinbase input' : (input.address || 'Non-address script') }}</p>
                <p v-if="input.txid" class="mt-2 select-text break-all font-mono text-[11px] leading-relaxed text-slate-500">Previous output: {{ input.txid }}:{{ input.vout }}</p>
              </li>
            </ol>
          </section>
          <section class="min-w-0" aria-labelledby="transaction-outputs-title">
            <h3 id="transaction-outputs-title" class="section-title mb-3 text-emerald-400">Outputs <span class="text-slate-500">({{ details.outputs.length }})</span></h3>
            <ol class="space-y-2">
              <li v-for="output in details.outputs" :key="output.index" class="detail-stat">
                <div class="mb-2 flex flex-wrap justify-between gap-2 text-sm"><span class="text-slate-500">#{{ output.index }}</span><b class="font-medium tabular-nums">{{ amount(output.value) }} {{ currencyLabel(currency) }}</b></div>
                <p class="select-text break-all font-mono text-xs leading-relaxed text-slate-300">{{ output.address || 'Non-address script' }}</p>
                <details class="mt-2 text-[11px] text-slate-500"><summary class="cursor-pointer py-1">Output script</summary><p class="mt-2 select-text break-all font-mono">{{ output.scriptHex || '(empty script)' }}</p></details>
              </li>
            </ol>
          </section>
        </div>
        <dl class="flex flex-wrap gap-x-6 gap-y-2 border-t border-slate-700/40 pt-4 text-xs text-slate-400">
          <div class="flex gap-2"><dt>Version</dt><dd class="text-slate-200">{{ details.version }}</dd></div>
          <div class="flex gap-2"><dt>Size</dt><dd class="text-slate-200">{{ details.size.toLocaleString('en-US') }} bytes</dd></div>
          <div class="flex gap-2"><dt>Lock time</dt><dd class="text-slate-200">{{ details.lockTime }}</dd></div>
        </dl>
      </template>
    </div>
  </dialog>
</template>