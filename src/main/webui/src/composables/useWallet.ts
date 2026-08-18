import { computed, onMounted, onScopeDispose, shallowRef } from 'vue';
import { createWalletStream, type WalletStreamOptions, type WalletStreamState } from '../services/walletStream.ts';

/** Vue owns the stream lifecycle; all wallet data comes from its server cache envelopes. */
export function useWallet(options: Omit<WalletStreamOptions, 'onState'> = {}) {
  const state = shallowRef<WalletStreamState>();
  const stream = createWalletStream({ ...options, onState: value => { state.value = value; } });
  state.value = stream.getState();

  // Compatibility with existing callers: resolves after requesting replay, not a rescan.
  function refresh(): Promise<void> {
    stream.refresh();
    return Promise.resolve();
  }

  onMounted(stream.start);
  onScopeDispose(stream.dispose);
  return {
    data: computed(() => state.value!.data),
    loading: computed(() => state.value!.loading),
    error: computed(() => state.value!.error),
    connection: computed(() => state.value!.connection),
    status: computed(() => state.value!.status),
    message: computed(() => state.value!.message),
    refresh,
  };
}