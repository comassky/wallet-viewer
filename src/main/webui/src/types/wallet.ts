/** API amounts are integer satoshis; fiat conversion is presentation-only. */
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

export type WalletStatus = 'loading' | 'syncing' | 'live' | 'offline' | 'error';

/** Complete server cache state, not a patch. Versions are scoped to one connection. */
export interface WalletEnvelope {
  version: number;
  status: WalletStatus;
  message: string | null;
  snapshot: WalletSnapshot | null;
}

export interface PriceRates {
  eur: number;
  usd: number;
  timestamp: number;
}