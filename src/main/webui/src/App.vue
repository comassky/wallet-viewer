<script setup lang="ts">
import { ref } from 'vue';
import { useWallet } from './composables/useWallet';
import { useCurrency } from './composables/useCurrency';
import type { ReceiveAddress, Transaction } from './types/wallet';
import DashboardHeader from './components/DashboardHeader.vue';
import PriceNotice from './components/PriceNotice.vue';
import BalanceCard from './components/BalanceCard.vue';
import ReceiveAddressCard from './components/ReceiveAddressCard.vue';
import TransactionsSection from './components/TransactionsSection.vue';
import UtxosSection from './components/UtxosSection.vue';
import ReceiveQrDialog from './components/ReceiveQrDialog.vue';
import WalletLiveStatus from './components/WalletLiveStatus.vue';
import TransactionDetailsDialog from './components/TransactionDetailsDialog.vue';

const { data, loading, error, refresh, connection, status, message } = useWallet();
const { currency, rates, ratesLoading, ratesError, fiat, refreshRates, amount } = useCurrency();
const qrDialog = ref<InstanceType<typeof ReceiveQrDialog> | null>(null);
const transactionDialog = ref<InstanceType<typeof TransactionDetailsDialog> | null>(null);

function refreshDashboard(): void {
  void refresh();
  void refreshRates();
}

function enlargeReceive(address: ReceiveAddress, trigger: HTMLButtonElement): void {
  void qrDialog.value?.open(address, trigger);
}

function inspectTransaction(transaction: Transaction, trigger: HTMLButtonElement): void {
  transactionDialog.value?.open(transaction, trigger);
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
      <TransactionsSection :transactions="data.transactions" :currency="currency" :amount="amount" @inspect="inspectTransaction" />
      <UtxosSection :utxos="data.utxos" :currency="currency" :amount="amount" />
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
  <TransactionDetailsDialog ref="transactionDialog" :currency="currency" :amount="amount" />
</template>
