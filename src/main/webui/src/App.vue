<script setup lang="ts">
import { ref } from 'vue';
import { useWallet } from './composables/useWallet';
import { useCurrency } from './composables/useCurrency';
import type { ReceiveAddress } from './types/wallet';
import DashboardHeader from './components/DashboardHeader.vue';
import PriceNotice from './components/PriceNotice.vue';
import BalanceCard from './components/BalanceCard.vue';
import ReceiveAddressCard from './components/ReceiveAddressCard.vue';
import TransactionsSection from './components/TransactionsSection.vue';
import UtxosSection from './components/UtxosSection.vue';
import ReceiveQrDialog from './components/ReceiveQrDialog.vue';
import WalletLiveStatus from './components/WalletLiveStatus.vue';

const { data, loading, error, refresh, connection, status, message } = useWallet();
const { currency, rates, ratesLoading, ratesError, fiat, refreshRates, amount } = useCurrency();
const qrDialog = ref<InstanceType<typeof ReceiveQrDialog> | null>(null);
const tabs = [
  { id: 'activity', label: 'Activity' },
  { id: 'utxos', label: 'UTXO' },
] as const;
const activeTab = ref<(typeof tabs)[number]['id']>('activity');
const tabButtons = ref<HTMLButtonElement[]>([]);

function navigateTabs(event: KeyboardEvent, index: number): void {
  let nextIndex: number;
  switch (event.key) {
    case 'ArrowRight': nextIndex = (index + 1) % tabs.length; break;
    case 'ArrowLeft': nextIndex = (index + tabs.length - 1) % tabs.length; break;
    case 'Home': nextIndex = 0; break;
    case 'End': nextIndex = tabs.length - 1; break;
    default: return;
  }
  event.preventDefault();
  activeTab.value = tabs[nextIndex].id;
  tabButtons.value[nextIndex]?.focus();
}

function refreshDashboard(): void {
  void refresh();
  void refreshRates();
}

function enlargeReceive(address: ReceiveAddress, trigger: HTMLButtonElement): void {
  void qrDialog.value?.open(address, trigger);
}

</script>

<template>
  <main lang="en-US" class="wallet-shell w-full px-4 pb-16 pt-6 sm:px-6 lg:px-10 lg:pt-9">
    <DashboardHeader :loading="loading" @refresh="refreshDashboard" />
    <WalletLiveStatus :connection="connection" :status="status" :message="message" :has-data="!!data" @retry="refresh" />
    <PriceNotice
      v-if="fiat"
      :currency="currency"
      :rates="rates"
      :loading="ratesLoading"
      :error="ratesError"
      :amount="amount"
      @retry="refreshRates"
    />

    <template v-if="data">
      <div class="grid grid-cols-1 gap-5 lg:grid-cols-2">
        <BalanceCard v-model:currency="currency" :balance="data.balance" :estimated="fiat && !!rates" :amount="amount" />
        <ReceiveAddressCard :receive="data.receiveAddress" @enlarge="enlargeReceive" />
      </div>
      <div class="mt-9">
        <div role="tablist" aria-label="Wallet details" class="mb-5 flex gap-2 border-b border-slate-800">
          <button
            v-for="(tab, index) in tabs"
            :id="`wallet-tab-${tab.id}`"
            :key="tab.id"
            ref="tabButtons"
            type="button"
            role="tab"
            :aria-selected="activeTab === tab.id"
            :aria-controls="`wallet-panel-${tab.id}`"
            :tabindex="activeTab === tab.id ? 0 : -1"
            class="-mb-px flex items-center gap-2 rounded-t-lg border-b-2 px-4 py-3 text-sm font-semibold transition focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent sm:px-5"
            :class="activeTab === tab.id ? 'border-accent bg-accent/5 text-accent' : 'border-transparent text-slate-400 hover:border-slate-600 hover:bg-slate-800/50 hover:text-slate-200'"
            @click="activeTab = tab.id"
            @keydown="navigateTabs($event, index)"
          >
            {{ tab.label }}
            <span class="rounded-full px-2 py-0.5 text-xs tabular-nums" :class="activeTab === tab.id ? 'bg-accent/15 text-accent' : 'bg-slate-800 text-slate-400'">
              {{ tab.id === 'activity' ? data.transactions.length : data.utxos.length }}
            </span>
          </button>
        </div>
        <div id="wallet-panel-activity" v-show="activeTab === 'activity'" role="tabpanel" aria-labelledby="wallet-tab-activity" tabindex="0" class="rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent">
          <TransactionsSection :transactions="data.transactions" :currency="currency" :amount="amount" />
        </div>
        <div id="wallet-panel-utxos" v-show="activeTab === 'utxos'" role="tabpanel" aria-labelledby="wallet-tab-utxos" tabindex="0" class="rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent">
          <UtxosSection :utxos="data.utxos" :currency="currency" :amount="amount" />
        </div>
      </div>
    </template>
    <div v-else-if="loading" class="py-20 text-center text-slate-400">
      Waiting for the wallet cache…
    </div>
    <div v-else class="py-20 text-center">
      <p role="alert" class="text-rose-400">{{ error || 'No wallet snapshot is available yet.' }}</p>
      <button type="button" @click="refresh" class="mt-4 rounded-lg border border-slate-700 bg-slate-800 px-4 py-2 text-sm hover:border-accent">
        Retry
      </button>
    </div>
  </main>

  <!-- Keep the native dialog and its snapshot alive across loading and error states. -->
  <ReceiveQrDialog ref="qrDialog" />
</template>
