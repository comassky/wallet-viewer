import { ref, watch, type Ref } from 'vue';
import type { WalletSnapshot } from '../types/wallet';
import { showToast } from './useToast.ts';

const storageKey = 'wallet-viewer.notifications';

function readEnabled(): boolean {
  try { return localStorage.getItem(storageKey) === '1'; } catch { return false; }
}

/** Toast (and, if enabled, a browser notification) when a new incoming transaction arrives. */
export function useIncomingNotifications(data: Ref<WalletSnapshot | null>) {
  const supported = typeof window !== 'undefined' && 'Notification' in window;
  const enabled = ref(supported && readEnabled() && Notification.permission === 'granted');
  // null until the first snapshot seeds the baseline, so existing history never notifies.
  let known: Set<string> | null = null;

  watch(enabled, value => {
    try { localStorage.setItem(storageKey, value ? '1' : '0'); } catch { /* Storage is optional. */ }
  });

  async function toggle(): Promise<void> {
    if (!supported) return;
    if (!enabled.value && Notification.permission !== 'granted') {
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') { enabled.value = false; return; }
    }
    enabled.value = !enabled.value;
  }

  function notify(sats: number): void {
    const text = `Incoming payment · ${sats.toLocaleString('en-US')} sat`;
    showToast(text, 4000);
    if (enabled.value && supported && Notification.permission === 'granted') {
      try { new Notification('Wallet Viewer', { body: text, icon: '/logo.png' }); } catch { /* Notifications are best-effort. */ }
    }
  }

  watch(data, snapshot => {
    const txs = snapshot?.transactions ?? [];
    if (known === null) { known = new Set(txs.map(tx => tx.txid)); return; }
    for (const tx of txs) {
      if (known.has(tx.txid)) continue;
      known.add(tx.txid);
      if (tx.type === 'received') notify(tx.amount);
    }
  });

  return { enabled, supported, toggle };
}
