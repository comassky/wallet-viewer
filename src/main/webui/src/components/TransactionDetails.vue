<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { currencyLabel, type Currency } from '../currency';
import type { Transaction, TransactionDetails } from '../types/wallet';
import { formatDate } from '../utils/format';
import { paginateItems, paginationRange } from '../utils/transactionGraph';
import TransactionGraph from './TransactionGraph.vue';
import TransactionBadge from './TransactionBadge.vue';
import ConfirmationStatus from './ConfirmationStatus.vue';
import CopyValue from './CopyValue.vue';

const props = defineProps<{
  idPrefix: string;
  transaction: Transaction;
  details: TransactionDetails | null;
  loading: boolean;
  error: string | null;
  currency: Currency;
  amount: (sats: number, signed?: boolean) => string;
}>();
defineEmits<{ retry: [] }>();

// Lists deliberately have independent pages from the graph and from one another.
const inputPageIndex = ref(0);
const outputPageIndex = ref(0);
const inputPage = computed(() => paginateItems(props.details?.inputs ?? [], inputPageIndex.value));
const outputPage = computed(() => paginateItems(props.details?.outputs ?? [], outputPageIndex.value));
const isCoinbase = computed(() => props.details?.inputs.some(input => input.coinbase) ?? false);

watch([() => props.transaction.txid, () => props.details?.txid], () => {
  inputPageIndex.value = outputPageIndex.value = 0;
}, { flush: 'sync' });
watch([() => props.details?.inputs.length, () => props.details?.outputs.length], () => {
  inputPageIndex.value = paginationRange(props.details?.inputs.length ?? 0, inputPageIndex.value).page;
  outputPageIndex.value = paginationRange(props.details?.outputs.length ?? 0, outputPageIndex.value).page;
}, { flush: 'sync' });
</script>

<template>
  <div class="min-w-0 space-y-6 p-4 sm:p-6">
    <div>
      <h3 class="mb-3 text-base font-semibold tracking-tight">Transaction details</h3>
      <div class="mb-3 flex flex-wrap items-center gap-3">
        <TransactionBadge :type="transaction.type" />
        <ConfirmationStatus :confirmations="transaction.confirmations" />
        <span class="text-xs text-slate-400">{{ formatDate(transaction.timestamp) }}</span>
      </div>
      <CopyValue :value="transaction.txid" label="transaction ID" class="break-all font-mono text-xs leading-relaxed text-slate-400" />
    </div>
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
      <dl class="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div class="detail-stat"><dt class="mb-2 text-xs text-slate-400">Total inputs</dt><dd class="break-all text-lg font-medium tabular-nums">{{ details.totalInput === null ? (isCoinbase ? 'Not applicable (coinbase)' : 'Unknown') : `${amount(details.totalInput)} ${currencyLabel(currency)}` }}</dd></div>
        <div class="detail-stat"><dt class="mb-2 text-xs text-slate-400">Total outputs</dt><dd class="break-all text-lg font-medium tabular-nums">{{ amount(details.totalOutput) }} {{ currencyLabel(currency) }}</dd></div>
        <div class="detail-stat"><dt class="mb-2 text-xs text-slate-400">Network fee</dt><dd class="break-all text-lg font-medium tabular-nums text-accent">{{ details.fee === null ? (isCoinbase ? 'Not applicable (coinbase)' : 'Unknown') : `${amount(details.fee)} ${currencyLabel(currency)}` }}</dd><dd v-if="details.fee !== null" class="mt-1 text-xs text-slate-500">{{ details.fee.toLocaleString('en-US') }} satoshis</dd></div>
      </dl>
      <TransactionGraph :id-prefix="idPrefix" :details="details" :currency="currency" :amount="amount" />
      <div class="grid min-w-0 gap-6 lg:grid-cols-2">
        <section class="min-w-0" :aria-labelledby="`${idPrefix}-inputs-title`">
          <h4 :id="`${idPrefix}-inputs-title`" class="section-title mb-3 text-sky-400">Inputs <span class="text-slate-500">({{ details.inputs.length }})</span></h4>
          <p :id="`${idPrefix}-inputs-list-count`" role="status" aria-live="polite" aria-atomic="true" class="mb-2 text-xs text-slate-400">{{ inputPage.total ? inputPage.start + 1 : 0 }}–{{ inputPage.end }} of {{ inputPage.total }} inputs · Page {{ inputPage.page + 1 }} / {{ inputPage.pageCount }}</p>
          <nav v-if="inputPage.pageCount > 1" :aria-labelledby="`${idPrefix}-inputs-title ${idPrefix}-inputs-list-nav-label`" class="mb-3 flex gap-2">
            <span :id="`${idPrefix}-inputs-list-nav-label`" class="sr-only">list pagination</span>
            <button type="button" class="button-secondary rounded-lg px-3 text-xs disabled:cursor-not-allowed disabled:opacity-40" :disabled="inputPage.page === 0" :aria-controls="`${idPrefix}-inputs-list`" :aria-describedby="`${idPrefix}-inputs-list-count`" @click="inputPageIndex = inputPage.page - 1">Previous</button>
            <button type="button" class="button-secondary rounded-lg px-3 text-xs disabled:cursor-not-allowed disabled:opacity-40" :disabled="inputPage.page + 1 === inputPage.pageCount" :aria-controls="`${idPrefix}-inputs-list`" :aria-describedby="`${idPrefix}-inputs-list-count`" @click="inputPageIndex = inputPage.page + 1">Next</button>
          </nav>
          <ol :id="`${idPrefix}-inputs-list`" :start="inputPage.start + 1" class="space-y-2">
            <li v-for="{ item: input, index } in inputPage.entries" :key="`${details.txid}-input-${index}`" :value="index + 1" class="detail-stat">
              <div class="mb-2 flex flex-wrap justify-between gap-2 text-sm"><span class="text-slate-500">#{{ index }}</span><b class="font-medium tabular-nums">{{ input.value === null ? (input.coinbase ? 'Not applicable (coinbase)' : 'Unknown value') : `${amount(input.value)} ${currencyLabel(currency)}` }}</b></div>
              <CopyValue v-if="input.address" :value="input.address" label="address" class="break-all font-mono text-xs leading-relaxed text-slate-300" />
              <p v-else class="text-xs text-slate-300">{{ input.coinbase ? 'Coinbase · newly created bitcoin' : 'Non-address script' }}</p>
              <p v-if="input.txid" class="mt-2 break-all font-mono text-[11px] leading-relaxed text-slate-500">Previous output: <CopyValue :value="input.txid" label="transaction ID" />:{{ input.vout }}</p>
            </li>
          </ol>
          <p v-if="!inputPage.total" class="text-xs text-slate-500">No inputs.</p>
        </section>
        <section class="min-w-0" :aria-labelledby="`${idPrefix}-outputs-title`">
          <h4 :id="`${idPrefix}-outputs-title`" class="section-title mb-3 text-emerald-400">Outputs <span class="text-slate-500">({{ details.outputs.length }})</span></h4>
          <p :id="`${idPrefix}-outputs-list-count`" role="status" aria-live="polite" aria-atomic="true" class="mb-2 text-xs text-slate-400">{{ outputPage.total ? outputPage.start + 1 : 0 }}–{{ outputPage.end }} of {{ outputPage.total }} outputs · Page {{ outputPage.page + 1 }} / {{ outputPage.pageCount }}</p>
          <nav v-if="outputPage.pageCount > 1" :aria-labelledby="`${idPrefix}-outputs-title ${idPrefix}-outputs-list-nav-label`" class="mb-3 flex gap-2">
            <span :id="`${idPrefix}-outputs-list-nav-label`" class="sr-only">list pagination</span>
            <button type="button" class="button-secondary rounded-lg px-3 text-xs disabled:cursor-not-allowed disabled:opacity-40" :disabled="outputPage.page === 0" :aria-controls="`${idPrefix}-outputs-list`" :aria-describedby="`${idPrefix}-outputs-list-count`" @click="outputPageIndex = outputPage.page - 1">Previous</button>
            <button type="button" class="button-secondary rounded-lg px-3 text-xs disabled:cursor-not-allowed disabled:opacity-40" :disabled="outputPage.page + 1 === outputPage.pageCount" :aria-controls="`${idPrefix}-outputs-list`" :aria-describedby="`${idPrefix}-outputs-list-count`" @click="outputPageIndex = outputPage.page + 1">Next</button>
          </nav>
          <ol :id="`${idPrefix}-outputs-list`" :start="outputPage.start + 1" class="space-y-2">
            <li v-for="{ item: output, index } in outputPage.entries" :key="`${details.txid}-output-${output.index}`" :value="index + 1" class="detail-stat">
              <div class="mb-2 flex flex-wrap justify-between gap-2 text-sm"><span class="text-slate-500">#{{ output.index }}</span><b class="font-medium tabular-nums">{{ amount(output.value) }} {{ currencyLabel(currency) }}</b></div>
              <CopyValue v-if="output.address" :value="output.address" label="address" class="break-all font-mono text-xs leading-relaxed text-slate-300" />
              <p v-else class="text-xs text-slate-300">Non-address script</p>
              <details class="mt-2 text-[11px] text-slate-500"><summary class="cursor-pointer py-1">Output script</summary><p class="mt-2 select-text break-all font-mono">{{ output.scriptHex || '(empty script)' }}</p></details>
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
    </template>
  </div>
</template>