<script setup lang="ts">
import { computed, nextTick, onWatcherCleanup, ref, useTemplateRef, watch } from 'vue';
import { currencyLabel, type Currency } from '../currency';
import type { Transaction, TransactionDetails } from '../types/wallet';
import { formatDate } from '../utils/format';
import { paginateItems, paginationRange } from '../utils/transactionGraph';
import { useModalDialog } from '../composables/useModalDialog';
import { useRovingTabs } from '../composables/useRovingTabs';
import { useMediaQuery } from '../composables/useMediaQuery';
import { usePrivacy } from '../composables/usePrivacy';
import TransactionGraph from './TransactionGraph.vue';
import TransactionBadge from './TransactionBadge.vue';
import ConfirmationStatus from './ConfirmationStatus.vue';
import CopyValue from './CopyValue.vue';
import UiIcon from './UiIcon.vue';

const props = defineProps<{
  transaction: Transaction | null;
  details: TransactionDetails | null;
  loading: boolean;
  error: string | null;
  currency: Currency;
  amount: (sats: number, signed?: boolean) => string;
}>();
const emit = defineEmits<{ close: []; retry: [] }>();

const idPrefix = 'transaction-dialog';
const { hidden } = usePrivacy();
const dialog = useTemplateRef<HTMLDialogElement>('dialog');
const { showModal, close, handleClose, closeOnBackdrop, isDisposed } = useModalDialog(dialog, () => emit('close'));
const tabs = [
  { id: 'graph', label: 'Graph', icon: 'graph' },
  { id: 'io', label: 'Inputs / Outputs', icon: 'list' },
] as const;
const tabButtons = useTemplateRef<HTMLButtonElement[]>('tabButtons');
const showGraph = useMediaQuery('(min-width: 768px)');
const visibleTabs = computed(() => tabs.filter(tab => showGraph.value || tab.id !== 'graph'));
const { activeTab, onKeydown } = useRovingTabs(() => visibleTabs.value.map(tab => tab.id), showGraph.value ? 'graph' : 'io', tabButtons);
watch(showGraph, visible => {
  if (!visible) activeTab.value = 'io';
});

// Lists deliberately have independent pages from the graph and from one another.
const inputPageIndex = ref(0);
const outputPageIndex = ref(0);
const inputPage = computed(() => paginateItems(props.details?.inputs ?? [], inputPageIndex.value));
const outputPage = computed(() => paginateItems(props.details?.outputs ?? [], outputPageIndex.value));
const isCoinbase = computed(() => props.details?.inputs.some(input => input.coinbase) ?? false);

// The parent owns the selection; opening/closing the native dialog follows it.
watch(() => props.transaction?.txid, async txid => {
  let cancelled = false;
  onWatcherCleanup(() => { cancelled = true; });
  const element = dialog.value;
  if (!element) return;
  if (txid) {
    activeTab.value = showGraph.value ? 'graph' : 'io';
    inputPageIndex.value = outputPageIndex.value = 0;
    await nextTick();
    if (cancelled || isDisposed()) return;
    showModal();
  } else if (element.open) {
    close();
  }
}, { flush: 'post' });

// A later fetch can arrive on a taller/shorter transaction: keep the page within range.
watch([() => props.details?.inputs.length, () => props.details?.outputs.length], () => {
  inputPageIndex.value = paginationRange(props.details?.inputs.length ?? 0, inputPageIndex.value).page;
  outputPageIndex.value = paginationRange(props.details?.outputs.length ?? 0, outputPageIndex.value).page;
}, { flush: 'sync' });
</script>

<template>
  <dialog
    id="transaction-details-dialog"
    ref="dialog"
    lang="en-US"
    aria-labelledby="transaction-details-title"
    class="transaction-dialog rounded-2xl border border-slate-700 bg-slate-900 p-4 text-slate-100 shadow-2xl max-md:m-0 max-md:h-dvh max-md:max-h-dvh max-md:w-full max-md:rounded-none max-md:border-0 max-md:pb-[max(1rem,env(safe-area-inset-bottom))] max-md:[&_.graph-page-button]:min-h-11 max-md:[&_.graph-page-button]:min-w-11 sm:p-6"
    @close="handleClose"
    @click="closeOnBackdrop"
  >
    <div class="relative mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 pr-14">
      <h2 id="transaction-details-title" class="text-lg font-semibold">Transaction details</h2>
      <template v-if="transaction">
        <TransactionBadge :type="transaction.type" />
        <ConfirmationStatus :confirmations="transaction.confirmations" />
        <span class="text-xs text-slate-400">{{ formatDate(transaction.timestamp) }}</span>
        <CopyValue :value="transaction.txid" label="transaction ID" class="min-w-0 break-all font-mono text-xs leading-relaxed text-slate-400" />
      </template>
      <button type="button" autofocus @click="close" aria-label="Close" class="button-secondary fixed right-4 top-4 z-20 inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-lg md:absolute md:right-0 md:top-0">
        <UiIcon name="close" class="h-4 w-4" />
      </button>
    </div>

    <template v-if="transaction">
      <div v-if="loading" role="status" class="flex flex-col items-center gap-4 py-10 text-sm text-slate-400">
        <span class="h-8 w-8 animate-spin rounded-full border-2 border-slate-700 border-t-accent motion-reduce:animate-none" aria-hidden="true" />
        Loading transaction and previous outputs from Electrum…
      </div>
      <div v-else-if="error" class="rounded-2xl border border-rose-500/20 bg-rose-500/5 p-5">
        <p role="alert" class="break-words text-sm text-rose-300">{{ error }}</p>
        <p class="mt-2 text-xs text-slate-400">Your Electrum server must be able to return this transaction and its previous transactions.</p>
        <button type="button" class="button-secondary mt-4 rounded-xl px-4 text-sm" @click="$emit('retry')">Try again</button>
      </div>
      <template v-else-if="details">
        <dl class="mb-4 grid grid-cols-1 gap-2 sm:grid-cols-3">
          <div class="rounded-lg border border-slate-700/40 bg-slate-950/40 px-3 py-2"><dt class="text-xs text-slate-400">Total inputs</dt><dd class="break-all text-base font-semibold tabular-nums">{{ details.totalInput === null ? (isCoinbase ? 'Not applicable (coinbase)' : 'Unknown') : `${amount(details.totalInput)} ${currencyLabel(currency)}` }}</dd></div>
          <div class="rounded-lg border border-slate-700/40 bg-slate-950/40 px-3 py-2"><dt class="text-xs text-slate-400">Total outputs</dt><dd class="break-all text-base font-semibold tabular-nums">{{ amount(details.totalOutput) }} {{ currencyLabel(currency) }}</dd></div>
          <div class="rounded-lg border border-slate-700/40 bg-slate-950/40 px-3 py-2"><dt class="text-xs text-slate-400">Network fee</dt><dd class="break-all text-base font-semibold tabular-nums text-accent">{{ details.fee === null ? (isCoinbase ? 'Not applicable (coinbase)' : 'Unknown') : `${amount(details.fee)} ${currencyLabel(currency)}` }}<span v-if="details.fee !== null && !hidden" class="ml-2 text-xs font-normal text-slate-500">{{ details.fee.toLocaleString('en-US') }} sat</span></dd></div>
        </dl>

        <div role="tablist" aria-label="Transaction detail views" class="mb-4 flex gap-2 border-b border-slate-800">
          <button
            v-for="(tab, index) in visibleTabs"
            :id="`${idPrefix}-tab-${tab.id}`"
            :key="tab.id"
            ref="tabButtons"
            type="button"
            role="tab"
            :aria-selected="activeTab === tab.id"
            :aria-controls="`${idPrefix}-panel-${tab.id}`"
            :tabindex="activeTab === tab.id ? 0 : -1"
            class="-mb-px inline-flex items-center gap-2 rounded-t-lg border-b-2 px-4 py-2.5 text-sm font-semibold transition focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
            :class="activeTab === tab.id ? 'border-accent bg-accent/5 text-accent' : 'border-transparent text-slate-400 hover:border-slate-600 hover:bg-slate-800/50 hover:text-slate-200'"
            @click="activeTab = tab.id"
            @keydown="onKeydown($event, index)"
          ><UiIcon :name="tab.icon" />{{ tab.label }}</button>
        </div>

        <div v-if="showGraph" v-show="activeTab === 'graph'" :id="`${idPrefix}-panel-graph`" role="tabpanel" :aria-labelledby="`${idPrefix}-tab-graph`" tabindex="0" class="rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent">
          <TransactionGraph :id-prefix="`${idPrefix}-graph`" :details="details" :currency="currency" :amount="amount" />
        </div>

        <div v-show="activeTab === 'io'" :id="`${idPrefix}-panel-io`" role="tabpanel" :aria-labelledby="`${idPrefix}-tab-io`" tabindex="0" class="space-y-6 rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent">
          <div class="grid min-w-0 gap-6 lg:grid-cols-2">
            <section class="min-w-0" :aria-labelledby="`${idPrefix}-inputs-title`">
              <div class="mb-3 flex min-h-11 flex-wrap items-center justify-between gap-x-4 gap-y-2">
                <h4 :id="`${idPrefix}-inputs-title`" class="section-title text-sky-400">Inputs <span class="text-slate-500">({{ details.inputs.length }})</span></h4>
                <div class="flex flex-wrap items-center gap-x-3 gap-y-1.5">
                  <p :id="`${idPrefix}-inputs-list-count`" role="status" aria-live="polite" aria-atomic="true" class="text-xs text-slate-400">{{ inputPage.total ? inputPage.start + 1 : 0 }}–{{ inputPage.end }} of {{ inputPage.total }} inputs · Page {{ inputPage.page + 1 }} / {{ inputPage.pageCount }}</p>
                  <nav v-if="inputPage.pageCount > 1" :aria-labelledby="`${idPrefix}-inputs-title ${idPrefix}-inputs-list-nav-label`" class="flex gap-1.5">
                    <span :id="`${idPrefix}-inputs-list-nav-label`" class="sr-only">list pagination</span>
                    <button type="button" class="graph-page-button" :disabled="inputPage.page === 0" aria-label="Previous inputs page" :aria-controls="`${idPrefix}-inputs-list`" :aria-describedby="`${idPrefix}-inputs-list-count`" @click="inputPageIndex = inputPage.page - 1"><UiIcon name="chevron-left" /></button>
                    <button type="button" class="graph-page-button" :disabled="inputPage.page + 1 === inputPage.pageCount" aria-label="Next inputs page" :aria-controls="`${idPrefix}-inputs-list`" :aria-describedby="`${idPrefix}-inputs-list-count`" @click="inputPageIndex = inputPage.page + 1"><UiIcon name="chevron-right" /></button>
                  </nav>
                </div>
              </div>
              <ol :id="`${idPrefix}-inputs-list`" :start="inputPage.start + 1" class="space-y-1.5">
                <li v-for="{ item: input, index } in inputPage.entries" :key="`${details.txid}-input-${index}`" :value="index + 1" class="transaction-io-entry rounded-lg border border-slate-700/40 bg-slate-950/40 px-2.5 py-1.5 transition hover:border-slate-600/60">
                  <div class="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-2.5 gap-y-2 sm:grid-cols-[auto_minmax(0,1fr)_auto]">
                    <span class="rounded-md bg-slate-800/70 px-1.5 py-0.5 text-[11px] font-semibold tabular-nums text-slate-400">#{{ index }}</span>
                    <CopyValue v-if="input.address" :value="input.address" label="address" class="min-w-0 break-all font-mono text-xs text-slate-200" />
                    <span v-else class="min-w-0 text-xs text-slate-300">{{ input.coinbase ? 'Coinbase · newly created bitcoin' : 'Non-address script' }}</span>
                    <b class="col-start-2 min-w-0 justify-self-end text-right text-sm font-semibold tabular-nums sm:col-start-auto">
                      <template v-if="input.value !== null">{{ amount(input.value) }} {{ currencyLabel(currency) }}</template>
                      <span v-else class="text-slate-500">{{ input.coinbase ? 'N/A' : 'Unknown' }}</span>
                    </b>
                  </div>
                  <details v-if="input.txid" class="mt-1.5 border-t border-slate-800/60 pt-1.5 text-[11px] text-slate-500">
                    <summary class="cursor-pointer select-none uppercase tracking-wide text-slate-600 transition hover:text-slate-300">Previous output</summary>
                    <p class="mt-1 flex min-w-0 flex-wrap items-center gap-x-1.5 break-all font-mono text-slate-400"><CopyValue :value="input.txid" label="transaction ID" /><span class="text-slate-500">:{{ input.vout }}</span></p>
                  </details>
                </li>
              </ol>
              <p v-if="!inputPage.total" class="text-xs text-slate-500">No inputs.</p>
            </section>
            <section class="min-w-0" :aria-labelledby="`${idPrefix}-outputs-title`">
              <div class="mb-3 flex min-h-11 flex-wrap items-center justify-between gap-x-4 gap-y-2">
                <h4 :id="`${idPrefix}-outputs-title`" class="section-title text-emerald-400">Outputs <span class="text-slate-500">({{ details.outputs.length }})</span></h4>
                <div class="flex flex-wrap items-center gap-x-3 gap-y-1.5">
                  <p :id="`${idPrefix}-outputs-list-count`" role="status" aria-live="polite" aria-atomic="true" class="text-xs text-slate-400">{{ outputPage.total ? outputPage.start + 1 : 0 }}–{{ outputPage.end }} of {{ outputPage.total }} outputs · Page {{ outputPage.page + 1 }} / {{ outputPage.pageCount }}</p>
                  <nav v-if="outputPage.pageCount > 1" :aria-labelledby="`${idPrefix}-outputs-title ${idPrefix}-outputs-list-nav-label`" class="flex gap-1.5">
                    <span :id="`${idPrefix}-outputs-list-nav-label`" class="sr-only">list pagination</span>
                    <button type="button" class="graph-page-button" :disabled="outputPage.page === 0" aria-label="Previous outputs page" :aria-controls="`${idPrefix}-outputs-list`" :aria-describedby="`${idPrefix}-outputs-list-count`" @click="outputPageIndex = outputPage.page - 1"><UiIcon name="chevron-left" /></button>
                    <button type="button" class="graph-page-button" :disabled="outputPage.page + 1 === outputPage.pageCount" aria-label="Next outputs page" :aria-controls="`${idPrefix}-outputs-list`" :aria-describedby="`${idPrefix}-outputs-list-count`" @click="outputPageIndex = outputPage.page + 1"><UiIcon name="chevron-right" /></button>
                  </nav>
                </div>
              </div>
              <ol :id="`${idPrefix}-outputs-list`" :start="outputPage.start + 1" class="space-y-1.5">
                <li v-for="{ item: output, index } in outputPage.entries" :key="`${details.txid}-output-${output.index}`" :value="index + 1" class="transaction-io-entry rounded-lg border border-slate-700/40 bg-slate-950/40 px-2.5 py-1.5 transition hover:border-slate-600/60">
                  <div class="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-2.5 gap-y-2 sm:grid-cols-[auto_minmax(0,1fr)_auto]">
                    <span class="rounded-md bg-slate-800/70 px-1.5 py-0.5 text-[11px] font-semibold tabular-nums text-slate-400">#{{ output.index }}</span>
                    <CopyValue v-if="output.address" :value="output.address" label="address" class="min-w-0 break-all font-mono text-xs text-slate-200" />
                    <span v-else class="min-w-0 text-xs text-slate-300">Non-address script</span>
                    <b class="col-start-2 min-w-0 justify-self-end text-right text-sm font-semibold tabular-nums sm:col-start-auto">{{ amount(output.value) }} {{ currencyLabel(currency) }}</b>
                  </div>
                  <details class="mt-1.5 border-t border-slate-800/60 pt-1.5 text-[11px] text-slate-500">
                    <summary class="cursor-pointer select-none uppercase tracking-wide text-slate-600 transition hover:text-slate-300">Output script</summary>
                    <p class="mt-1 select-text break-all font-mono text-slate-400">{{ output.scriptHex || '(empty script)' }}</p>
                  </details>
                </li>
              </ol>
              <p v-if="!outputPage.total" class="text-xs text-slate-500">No outputs.</p>
            </section>
          </div>
          <dl class="flex flex-wrap gap-x-6 gap-y-2 border-t border-slate-700/40 pt-4 text-xs text-slate-400">
            <div class="flex gap-2"><dt>Version</dt><dd class="text-slate-200">{{ details.version }}</dd></div>
            <div class="flex gap-2"><dt>Size</dt><dd class="text-slate-200">{{ details.size.toLocaleString('en-US') }} bytes</dd></div>
            <div class="flex gap-2"><dt>Lock time</dt><dd class="text-slate-200">{{ details.lockTime }}</dd></div>
          </dl>
        </div>
      </template>
    </template>
  </dialog>
</template>
