import { onScopeDispose, ref, shallowRef } from 'vue';
import type { ElectrumServer } from '@/types/wallet';
import { walletApi } from '@/services/walletApi.ts';

/** Fetch local backend metadata on demand, never poll Electrum or the wallet. */
export function useElectrumServer() {
  const server = shallowRef<ElectrumServer | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  let controller: AbortController | null = null;
  let disposed = false;

  function reset() {
    controller?.abort();
    controller = null;
    server.value = null;
    error.value = null;
    loading.value = false;
  }

  async function load() {
    if (disposed || loading.value) return;
    const request = new AbortController();
    controller = request;
    loading.value = true;
    error.value = null;
    try {
      const result = await walletApi.server({ signal: request.signal, timeoutMs: 5000 });
      if (controller === request) server.value = result;
    } catch {
      if (controller === request) {
        server.value = null;
        error.value = 'Server information unavailable.';
      }
    } finally {
      if (controller === request) { loading.value = false; controller = null; }
    }
  }
  onScopeDispose(() => { disposed = true; reset(); });
  return { server, loading, error, load, reset };
}