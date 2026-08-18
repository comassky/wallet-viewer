// Thin REST client for the wallet backend. All calls hit the Quarkus API under /api/wallet.

export interface Balance {
  confirmed: number;
  unconfirmed: number;
  total: number;
}

export interface Utxo {
  txid: string;
  vout: number;
  value: number;
  height: number;
  confirmations: number;
  address: string;
}

export type TxType = 'received' | 'sent' | 'self';

export interface Transaction {
  txid: string;
  amount: number;
  received: number;
  sent: number;
  height: number;
  confirmations: number;
  timestamp: number | null;
  type: TxType;
}

export interface ReceiveAddress {
  index: number;
  address: string;
  path: string;
}

export interface WalletSnapshot {
  balance: Balance;
  utxos: Utxo[];
  transactions: Transaction[];
  receiveAddress: ReceiveAddress;
}

export interface RequestOptions {
  signal?: AbortSignal;
}

const BASE = '/api/wallet';
const REQUEST_TIMEOUT_MS = 120_000;

async function request<T>(path: string, { signal }: RequestOptions = {}): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort(signal?.reason);
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) abort();
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, REQUEST_TIMEOUT_MS);
  try {
    let res: Response;
    try {
      res = await fetch(BASE + path, {
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      });
    } catch (e) {
      if (controller.signal.aborted) throw e;
      throw new Error(`Réseau injoignable : ${e instanceof Error ? e.message : String(e)}`);
    }
    if (!res.ok) {
      const body = await res.text();
      throw new Error(`HTTP ${res.status}${body ? ` — ${body}` : ''}`);
    }
    return (await res.json()) as T;
  } catch (e) {
    if (timedOut) throw new Error('Le serveur met trop de temps à répondre. Réessayez.');
    throw e;
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener('abort', abort);
  }
}

export const walletApi = {
  snapshot: (opts?: RequestOptions) => request<WalletSnapshot>('', opts),
  balance: (opts?: RequestOptions) => request<Balance>('/balance', opts),
  transactions: (opts?: RequestOptions) => request<Transaction[]>('/transactions', opts),
  utxos: (opts?: RequestOptions) => request<Utxo[]>('/utxos', opts),
  receive: (opts?: RequestOptions) => request<ReceiveAddress>('/receive', opts),
  receiveAt: (index: number, opts?: RequestOptions) => request<ReceiveAddress>(`/receive/${index}`, opts),
  // QR endpoints return PNG images, used directly as <img src>.
  qrUrl: (cacheBust?: number) => `${BASE}/receive/qr${cacheBust ? `?ts=${cacheBust}` : ''}`,
  qrAtUrl: (index: number) => `${BASE}/receive/${index}/qr`,
};
