import { onScopeDispose, ref, shallowRef } from 'vue';
import { walletApi } from '@/services/walletApi.ts';
import type { TransactionDetails } from '@/types/wallet';

/** Detail requests are independent of the live wallet stream and cancelled on close. */
export function useTransactionDetails() {
  const details = shallowRef<TransactionDetails | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  let controller: AbortController | null = null;
  let disposed = false;

  function cancel(): void {
    controller?.abort();
    controller = null;
    loading.value = false;
  }

  async function load(txid: string): Promise<void> {
    if (disposed) return;
    cancel();
    const request = new AbortController();
    controller = request;
    details.value = null;
    error.value = null;
    loading.value = true;
    try {
      const result = await walletApi.transactionDetails(txid, { signal: request.signal });
      if (controller !== request) return;
      if (result.txid !== txid) throw new Error('The returned transaction does not match the selected transaction.');
      details.value = result;
    } catch (failure) {
      if (controller === request) error.value = failure instanceof Error ? failure.message : 'Transaction details are unavailable.';
    } finally {
      if (controller === request) {
        loading.value = false;
        controller = null;
      }
    }
  }

  onScopeDispose(() => { disposed = true; cancel(); });
  return { details, loading, error, load, cancel };
}