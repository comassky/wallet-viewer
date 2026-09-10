import { watch, type Ref } from 'vue';
import type { WalletSnapshot } from '../types/wallet';
import { showToast } from './useToast.ts';

/** Show an in-app toast when a new transaction appears in the wallet snapshot. */
export function useIncomingNotifications(data: Ref<WalletSnapshot | null>): void {
  // null until the first snapshot seeds the baseline, so existing history never toasts.
  let known: Set<string> | null = null;
  watch(data, snapshot => {
    if (!snapshot) return;
    const txs = snapshot.transactions;
    if (known === null) { known = new Set(txs.map(tx => tx.txid)); return; }
    for (const tx of txs) {
      if (known.has(tx.txid)) continue;
      known.add(tx.txid);
      const label = tx.type === 'received' ? 'Incoming payment' : tx.type === 'sent' ? 'Outgoing payment' : 'New transaction';
      showToast(label, 4000);
    }
  });
}
