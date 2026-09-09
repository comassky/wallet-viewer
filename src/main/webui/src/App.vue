<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { walletApi, type WalletSnapshot, type TxType } from './api';

const data = ref<WalletSnapshot | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);
const copied = ref(false);
const copyError = ref<string | null>(null);
let copyTimer: ReturnType<typeof setTimeout> | undefined;
const controller = new AbortController();
const btcFormatter = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 8,
  maximumFractionDigits: 8,
});

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    data.value = await walletApi.snapshot({ signal: controller.signal });
  } catch (e) {
    if (!controller.signal.aborted) {
      error.value = e instanceof Error ? e.message : String(e);
    }
  } finally {
    loading.value = false;
  }
}
onMounted(load);
onUnmounted(() => {
  controller.abort();
  clearTimeout(copyTimer);
});

function btc(sats: number): string {
  return btcFormatter.format(sats / 1e8);
}
function shortId(id: string): string {
  return id ? `${id.slice(0, 10)}…${id.slice(-6)}` : '';
}
function fmtDate(ts: number | null): string {
  return ts ? new Date(ts * 1000).toLocaleString() : '—';
}
async function copyAddr(text: string): Promise<void> {
  clearTimeout(copyTimer);
  copied.value = false;
  copyError.value = null;
  try {
    await navigator.clipboard.writeText(text);
    if (controller.signal.aborted) return;
    copied.value = true;
    copyTimer = setTimeout(() => (copied.value = false), 1500);
  } catch {
    copyError.value = 'Copie indisponible. Sélectionnez et copiez l’adresse manuellement.';
  }
}

// Use the displayed index: a second snapshot could otherwise yield a different address.
const qrUrl = computed(() => data.value ? walletApi.qrAtUrl(data.value.receiveAddress.index) : undefined);
// data is guaranteed non-null wherever these are rendered (v-if="data").
const balance = computed(() => data.value!.balance);
const receive = computed(() => data.value!.receiveAddress);
const transactions = computed(() => data.value!.transactions);
const utxos = computed(() => data.value!.utxos);

const tagClass: Record<TxType, string> = {
  received: 'bg-emerald-500/15 text-emerald-400',
  sent: 'bg-rose-500/15 text-rose-400',
  self: 'bg-sky-500/15 text-sky-400',
};
const tagLabel: Record<TxType, string> = { received: 'Reçu', sent: 'Envoyé', self: 'Interne' };
</script>

<template>
  <div class="mx-auto max-w-5xl px-4 pb-16 pt-6">
    <header class="mb-6 flex items-center justify-between">
      <h1 class="flex items-center gap-2 text-xl font-semibold">
        <span class="text-2xl text-accent">₿</span> Wallet Viewer
      </h1>
      <button
        @click="load"
        :disabled="loading"
        class="rounded-lg border border-slate-700 bg-slate-800 px-3.5 py-2 text-sm text-slate-100 transition hover:border-accent disabled:opacity-50"
      >
        {{ loading ? 'Chargement…' : '↻ Rafraîchir' }}
      </button>
    </header>

    <div v-if="loading && !data" class="py-20 text-center text-slate-400">
      Connexion au serveur Electrum…
    </div>
    <div v-else-if="error" class="py-20 text-center text-rose-400">Erreur : {{ error }}</div>

    <template v-else-if="data">
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
        <!-- Balance -->
        <section class="rounded-xl border border-slate-800 bg-slate-900 p-5">
          <h2 class="mb-3.5 text-xs font-medium uppercase tracking-wider text-slate-400">Solde</h2>
          <div class="text-4xl font-semibold tabular-nums">
            {{ btc(balance.total) }}<span class="ml-1.5 text-base text-slate-400">BTC</span>
          </div>
          <div class="mt-2 flex flex-wrap gap-5 text-sm text-slate-400">
            <span>Confirmé : <b class="font-semibold text-slate-100">{{ btc(balance.confirmed) }}</b></span>
            <span v-if="balance.unconfirmed !== 0">
              Non confirmé : <b class="font-semibold text-slate-100">{{ btc(balance.unconfirmed) }}</b>
            </span>
          </div>
        </section>

        <!-- Receive -->
        <section class="rounded-xl border border-slate-800 bg-slate-900 p-5">
          <h2 class="mb-3.5 text-xs font-medium uppercase tracking-wider text-slate-400">
            Adresse de réception
          </h2>
          <div class="flex items-center gap-5">
            <img
              :src="qrUrl"
              alt="QR code de l'adresse de réception"
              class="h-36 w-36 rounded-lg bg-white p-2"
            />
            <div class="min-w-0 flex-1">
              <div class="my-2 break-all rounded-lg border border-slate-700 bg-slate-800 p-2.5 font-mono text-sm">
                {{ receive.address }}
              </div>
              <div class="font-mono text-xs text-slate-400">{{ receive.path }}</div>
              <button
                @click="copyAddr(receive.address)"
                class="mt-2 rounded-md border border-slate-700 bg-slate-800 px-2.5 py-1 text-xs transition hover:border-accent"
              >
                {{ copied ? '✓ Copié' : 'Copier' }}
              </button>
              <p v-if="copyError" role="status" class="mt-2 text-xs text-rose-400">{{ copyError }}</p>
            </div>
          </div>
        </section>
      </div>

      <!-- Transactions -->
      <section class="mt-6">
        <h2 class="mb-3 text-xs font-medium uppercase tracking-wider text-slate-400">
          Transactions ({{ transactions.length }})
        </h2>
        <div class="overflow-x-auto rounded-xl border border-slate-800 bg-slate-900">
          <table v-if="transactions.length" class="w-full text-sm">
            <thead>
              <tr class="text-xs uppercase text-slate-400">
                <th class="px-3 py-3 text-left font-medium">Type</th>
                <th class="px-3 py-3 text-left font-medium">Transaction</th>
                <th class="px-3 py-3 text-left font-medium">Date</th>
                <th class="px-3 py-3 text-right font-medium">Montant (BTC)</th>
                <th class="px-3 py-3 text-right font-medium">Confirmations</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="tx in transactions"
                :key="tx.txid"
                class="border-t border-slate-800 hover:bg-slate-800/50"
              >
                <td class="px-3 py-2.5">
                  <span class="rounded-full px-2 py-0.5 text-xs" :class="tagClass[tx.type]">
                    {{ tagLabel[tx.type] }}
                  </span>
                </td>
                <td class="px-3 py-2.5">
                  <a
                    :href="`https://mempool.space/tx/${tx.txid}`"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="font-mono text-sky-400 hover:underline"
                  >
                    {{ shortId(tx.txid) }}
                  </a>
                </td>
                <td class="px-3 py-2.5 text-slate-300">{{ fmtDate(tx.timestamp) }}</td>
                <td
                  class="px-3 py-2.5 text-right tabular-nums"
                  :class="tx.amount >= 0 ? 'text-emerald-400' : 'text-rose-400'"
                >
                  {{ tx.amount >= 0 ? '+' : '−' }}{{ btc(Math.abs(tx.amount)) }}
                </td>
                <td class="px-3 py-2.5 text-right">
                  <span v-if="tx.confirmations > 0" class="text-xs text-slate-400">
                    {{ tx.confirmations }}
                  </span>
                  <span v-else class="text-xs text-accent">En attente</span>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="p-5 text-slate-400">Aucune transaction.</div>
        </div>
      </section>

      <!-- UTXOs -->
      <section class="mt-6">
        <h2 class="mb-3 text-xs font-medium uppercase tracking-wider text-slate-400">
          UTXO ({{ utxos.length }})
        </h2>
        <div class="overflow-x-auto rounded-xl border border-slate-800 bg-slate-900">
          <table v-if="utxos.length" class="w-full text-sm">
            <thead>
              <tr class="text-xs uppercase text-slate-400">
                <th class="px-3 py-3 text-left font-medium">Adresse</th>
                <th class="px-3 py-3 text-left font-medium">Outpoint</th>
                <th class="px-3 py-3 text-right font-medium">Valeur (BTC)</th>
                <th class="px-3 py-3 text-right font-medium">Confirmations</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="u in utxos"
                :key="`${u.txid}:${u.vout}`"
                class="border-t border-slate-800 hover:bg-slate-800/50"
              >
                <td class="px-3 py-2.5 font-mono text-slate-300">{{ shortId(u.address) }}</td>
                <td class="px-3 py-2.5 font-mono text-slate-300">{{ shortId(u.txid) }}:{{ u.vout }}</td>
                <td class="px-3 py-2.5 text-right tabular-nums">{{ btc(u.value) }}</td>
                <td class="px-3 py-2.5 text-right">
                  <span v-if="u.confirmations > 0" class="text-xs text-slate-400">
                    {{ u.confirmations }}
                  </span>
                  <span v-else class="text-xs text-accent">En attente</span>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="p-5 text-slate-400">Aucun UTXO.</div>
        </div>
      </section>
    </template>
  </div>
</template>
