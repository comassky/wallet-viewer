import { usePolledResource } from './usePolledResource.ts';
import { walletApi } from '../services/walletApi.ts';
import type { FeeRates } from '../types/wallet';

/** Recommended mempool fee estimates, refreshed on an interval; proxied by the backend. */
export function useFees() {
  const { data: fees, loading, error } = usePolledResource<FeeRates | null>(walletApi.fees, null);
  return { fees, loading, error };
}
