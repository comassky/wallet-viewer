import { onMounted, onScopeDispose, ref } from 'vue';
import { walletApi } from '../services/walletApi';
import type { BalancePoint } from '../types/wallet';

/** Daily balance valued in fiat, computed and cached by the backend. */
export function useBalanceHistory() {
  const history = ref<BalancePoint[]>([]);
  const loading = ref(false);
  const error = ref(false);
  const controller = new AbortController();
  let timer: ReturnType<typeof setInterval> | undefined;

  async function refresh(): Promise<void> {
    if (controller.signal.aborted || loading.value) return;
    loading.value = true;
    try {
      const result = await walletApi.balanceHistory({ signal: controller.signal });
      if (controller.signal.aborted) return;
      history.value = result;
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

  return { history, loading, error };
}
