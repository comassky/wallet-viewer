import { onMounted, onScopeDispose, ref } from 'vue';
import { walletApi } from '../services/walletApi';
import type { FeeRates } from '../types/wallet';

/** Recommended mempool fee estimates, refreshed on an interval; proxied by the backend. */
export function useFees() {
  const fees = ref<FeeRates | null>(null);
  const loading = ref(false);
  const error = ref(false);
  const controller = new AbortController();
  let timer: ReturnType<typeof setInterval> | undefined;

  async function refresh(): Promise<void> {
    if (controller.signal.aborted || loading.value) return;
    loading.value = true;
    try {
      const result = await walletApi.fees({ signal: controller.signal });
      if (controller.signal.aborted) return;
      fees.value = result;
      error.value = false;
    } catch {
      if (!controller.signal.aborted) error.value = true;
    } finally {
      loading.value = false;
    }
  }

  onMounted(() => {
    void refresh();
    timer = setInterval(() => void refresh(), 60_000);
  });
  onScopeDispose(() => {
    controller.abort();
    clearInterval(timer);
  });

  return { fees, loading, error };
}
