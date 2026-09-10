<script setup lang="ts">
import { computed, defineAsyncComponent, useTemplateRef, watch } from 'vue';
import { useWallet } from './composables/useWallet';
import { useCurrency } from './composables/useCurrency';
import type { ReceiveAddress } from './types/wallet';
import DashboardHeader from './components/DashboardHeader.vue';
import BalanceCard from './components/BalanceCard.vue';
import ReceiveAddressCard from './components/ReceiveAddressCard.vue';
import TransactionsSection from './components/TransactionsSection.vue';
import UtxosSection from './components/UtxosSection.vue';
import ReceiveQrDialog from './components/ReceiveQrDialog.vue';
import UiIcon from './components/UiIcon.vue';
import ToastHost from './components/ToastHost.vue';
import WalletSkeleton from './components/WalletSkeleton.vue';
import { useIncomingNotifications } from './composables/useIncomingNotifications';
import { useRovingTabs } from './composables/useRovingTabs';
import { useMediaQuery } from './composables/useMediaQuery';
import { formatDate } from './utils/format';

const { data, updatedAt, loading, error, refresh, connection, status, message } = useWallet();
const { currency, fiatCurrency, rates, ratesLoading, ratesError, amount } = useCurrency();
useIncomingNotifications(data);
const qrDialog = useTemplateRef<InstanceType<typeof ReceiveQrDialog>>('qrDialog');
const appVersion = __APP_VERSION__;
const BalanceChart = defineAsyncComponent(() => import('./components/BalanceChart.vue'));
const tabs = [
  { id: 'activity', label: 'Activity', icon: 'activity' },
  { id: 'utxos', label: 'UTXO', icon: 'coins' },
  { id: 'chart', label: 'Chart', icon: 'chart' },
] as const;
const tabButtons = useTemplateRef<HTMLButtonElement[]>('tabButtons');
const showCharts = useMediaQuery('(min-width: 768px)');
const visibleTabs = computed(() => tabs.filter(tab => showCharts.value || tab.id !== 'chart'));
const { activeTab, onKeydown } = useRovingTabs(() => visibleTabs.value.map(tab => tab.id), 'activity', tabButtons);
watch(showCharts, visible => {
  if (!visible && activeTab.value === 'chart') activeTab.value = 'activity';
});

function enlargeReceive(address: ReceiveAddress, trigger: HTMLButtonElement): void {
  void qrDialog.value?.open(address, trigger);
}

</script>

<template>
  <main lang="en-US" class="wallet-shell w-full px-3 pb-8 pt-4 sm:px-6 sm:pb-16 sm:pt-6 lg:px-10 lg:pt-9">
    <DashboardHeader :connection="connection" :status="status" :message="message" @retry="refresh" />
    <Transition name="banner">
      <p v-if="data && (connection !== 'connected' || status !== 'live')" role="status" class="mb-5 rounded-xl border border-amber-400/20 bg-amber-400/5 px-4 py-3 text-xs text-amber-300">
        {{ message || 'Synchronizing wallet data.' }} Showing the last received snapshot; it may be out of date.
      </p>
    </Transition>

    <template v-if="data">
      <p v-if="updatedAt !== null" class="mb-4 text-xs text-slate-400">Last successful sync: {{ formatDate(updatedAt) }}</p>
      <p v-if="data.discovery && !data.discovery.complete" role="alert" class="mb-5 rounded-lg border border-amber-400/30 bg-amber-400/5 px-4 py-3 text-sm text-amber-300">
        Incomplete address discovery: {{ data.discovery.receiveScanned }} receive and {{ data.discovery.changeScanned }} change addresses scanned (limit {{ data.discovery.addressLimit }} per chain).
        The balance may exclude funds. Increase WALLET_MAX_ADDRESSES before relying on this balance or receiving more Bitcoin.
      </p>
      <div class="grid grid-cols-1 gap-3 sm:gap-5 lg:grid-cols-2">
        <BalanceCard v-model:currency="currency" v-model:fiat-currency="fiatCurrency" :balance="data.balance" :rates="rates" :rates-loading="ratesLoading" :rates-error="ratesError" :amount="amount" />
        <ReceiveAddressCard :receive="data.receiveAddress" @enlarge="enlargeReceive" />
      </div>
      <div class="mt-6 sm:mt-9">
        <div role="tablist" aria-label="Wallet details" class="mb-5 flex gap-2 border-b border-slate-800">
          <button
            v-for="(tab, index) in visibleTabs"
            :id="`wallet-tab-${tab.id}`"
            :key="tab.id"
            ref="tabButtons"
            type="button"
            role="tab"
            :aria-selected="activeTab === tab.id"
            :aria-controls="`wallet-panel-${tab.id}`"
            :tabindex="activeTab === tab.id ? 0 : -1"
            class="-mb-px flex min-w-0 flex-1 items-center justify-center gap-2 rounded-t-lg border-b-2 px-3 py-3 text-sm font-semibold transition focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent sm:flex-none sm:px-5"
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
        <div v-if="showCharts" id="wallet-panel-chart" v-show="activeTab === 'chart'" role="tabpanel" aria-labelledby="wallet-tab-chart" tabindex="0" class="rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent">
          <BalanceChart v-if="activeTab === 'chart'" :currency="currency" :fiat-currency="fiatCurrency" :amount="amount" :transactions="data.transactions" />
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
