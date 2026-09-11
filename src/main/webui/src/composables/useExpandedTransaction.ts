import { ref, watch, type Ref } from 'vue';
import type { Transaction } from '@/types/wallet';
import { useTransactionDetails } from '@/composables/useTransactionDetails.ts';

/** One shared request for both responsive views; collapsing cancels pending work. */
export function useExpandedTransaction(transactions: Ref<Transaction[]>) {
  const expandedTxid = ref<string | null>(null);
  const { details, loading, error, load, cancel } = useTransactionDetails();

  function toggle(txid: string): void {
    if (expandedTxid.value === txid) {
      expandedTxid.value = null;
      cancel();
    } else {
      expandedTxid.value = txid;
      void load(txid);
    }
  }

  function retry(): void {
    if (expandedTxid.value) void load(expandedTxid.value);
  }

  watch(() => transactions.value.map(tx => tx.txid), ids => {
    if (expandedTxid.value && !ids.includes(expandedTxid.value)) {
      expandedTxid.value = null;
      cancel();
    }
  });

  return { expandedTxid, details, loading, error, toggle, retry };
}