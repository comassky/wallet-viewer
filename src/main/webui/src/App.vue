<script setup lang="ts">
import { ref } from 'vue';
import { useWallet } from './composables/useWallet';
import { useCurrency } from './composables/useCurrency';
import type { ReceiveAddress } from './types/wallet';
import DashboardHeader from './components/DashboardHeader.vue';
import BalanceCard from './components/BalanceCard.vue';
import ReceiveAddressCard from './components/ReceiveAddressCard.vue';
import TransactionsSection from './components/TransactionsSection.vue';
import UtxosSection from './components/UtxosSection.vue';
import BalanceChart from './components/BalanceChart.vue';
import ReceiveQrDialog from './components/ReceiveQrDialog.vue';
import UiIcon from './components/UiIcon.vue';
import ToastHost from './components/ToastHost.vue';
import WalletSkeleton from './components/WalletSkeleton.vue';
import { useIncomingNotifications } from './composables/useIncomingNotifications';
import { useRovingTabs } from './composables/useRovingTabs';

const { data, loading, error, refresh, connection, status, message } = useWallet();
const { currency, fiatCurrency, rates, ratesLoading, ratesError, amount } = useCurrency();
useIncomingNotifications(data);
const qrDialog = ref<InstanceType<typeof ReceiveQrDialog> | null>(null);
const appVersion = __APP_VERSION__;
const tabs = [
  { id: 'activity', label: 'Activity', icon: 'activity' },
  { id: 'utxos', label: 'UTXO', icon: 'coins' },
  { id: 'chart', label: 'Chart', icon: 'chart' },
] as const;
const tabButtons = ref<HTMLButtonElement[]>([]);
const { activeTab, onKeydown } = useRovingTabs(tabs.map(tab => tab.id), 'activity', tabButtons);

function enlargeReceive(address: ReceiveAddress, trigger: HTMLButtonElement): void {
  void qrDialog.value?.open(address, trigger);
}

</script>

<template>
  <main lang="en-US" class="wallet-shell w-full px-4 pb-16 pt-6 sm:px-6 lg:px-10 lg:pt-9">
    <DashboardHeader :connection="connection" :status="status" :message="message" @retry="refresh" />
    <Transition name="banner">
      <p v-if="data && (connection !== 'connected' || status !== 'live')" role="status" class="mb-5 rounded-xl border border-amber-400/20 bg-amber-400/5 px-4 py-3 text-xs text-amber-300">
        {{ message || 'Synchronizing wallet data.' }} Showing the last received snapshot; it may be out of date.
      </p>
    </Transition>

    <template v-if="data">
      <div class="grid grid-cols-1 gap-5 lg:grid-cols-2">
        <BalanceCard v-model:currency="currency" v-model:fiat-currency="fiatCurrency" :balance="data.balance" :rates="rates" :rates-loading="ratesLoading" :rates-error="ratesError" :amount="amount" />
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
            @keydown="onKeydown($event, index)"
          >
            <UiIcon :name="tab.icon" />{{ tab.label }}
            <span v-if="tab.id !== 'chart'" class="rounded-full px-2 py-0.5 text-xs tabular-nums" :class="activeTab === tab.id ? 'bg-accent/15 text-accent' : 'bg-slate-800 text-slate-400'">
              {{ tab.id === 'activity' ? data.transactions.length : data.utxos.length }}
            </span>
          </button>
        </div>
        <div id="wallet-panel-activity" v-show="activeTab === 'activity'" role="tabpanel" aria-labelledby="wallet-tab-activity" tabindex="0" class="rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent">
          <TransactionsSection :transactions="data.transactions" :currency="currency" :fiat-currency="fiatCurrency" :rates="rates" :amount="amount" />
        </div>
        <div id="wallet-panel-utxos" v-show="activeTab === 'utxos'" role="tabpanel" aria-labelledby="wallet-tab-utxos" tabindex="0" class="rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent">
          <UtxosSection :utxos="data.utxos" :currency="currency" :amount="amount" />
        </div>
        <div id="wallet-panel-chart" v-show="activeTab === 'chart'" role="tabpanel" aria-labelledby="wallet-tab-chart" tabindex="0" class="rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent">
          <BalanceChart :currency="currency" :fiat-currency="fiatCurrency" :amount="amount" />
        </div>
      </div>
    </template>
    <WalletSkeleton v-else-if="loading" />
    <div v-else class="py-20 text-center">
      <p role="alert" class="text-rose-400">{{ error || 'No wallet snapshot is available yet.' }}</p>
      <button type="button" @click="refresh" class="mt-4 rounded-lg border border-slate-700 bg-slate-800 px-4 py-2 text-sm hover:border-accent">
        Retry
      </button>
    </div>
  </main>

  <footer class="wallet-shell w-full px-4 pb-8 text-xs text-slate-500 sm:px-6 lg:px-10">
    <div class="flex flex-wrap items-center justify-center gap-x-2 gap-y-1 border-t border-slate-800 pt-6">
      <span>Wallet Viewer v{{ appVersion }}</span>
      <span aria-hidden="true">·</span>
      <a href="https://github.com/comassky/wallet-viewer" target="_blank" rel="noopener noreferrer" class="inline-flex items-center gap-1.5 font-medium text-slate-400 transition hover:text-accent">
        <UiIcon name="github" />GitHub
      </a>
    </div>
  </footer>

  <!-- Keep the native dialog and its snapshot alive across loading and error states. -->
  <ReceiveQrDialog ref="qrDialog" />
  <ToastHost />
</template>
