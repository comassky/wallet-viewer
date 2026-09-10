import { usePolledResource } from './usePolledResource.ts';
import { walletApi } from '../services/walletApi.ts';
import type { BalancePoint } from '../types/wallet';

/** Daily balance valued in fiat, computed and cached by the backend. */
export function useBalanceHistory() {
  const { data: history, loading, error, refresh } = usePolledResource<BalancePoint[]>(walletApi.balanceHistory, []);
  return { history, loading, error, refresh };
}
