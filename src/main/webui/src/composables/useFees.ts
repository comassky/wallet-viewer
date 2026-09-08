import { usePolledResource } from './usePolledResource';
import { walletApi } from '../services/walletApi';
import type { FeeRates } from '../types/wallet';

/** Recommended mempool fee estimates, refreshed on an interval; proxied by the backend. */
export function useFees() {
  const { data: fees, loading, error } = usePolledResource<FeeRates | null>(walletApi.fees, null);
  return { fees, loading, error };
}
