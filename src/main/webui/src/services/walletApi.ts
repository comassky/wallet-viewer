import { requestJson, type RequestOptions } from './http.ts';
import type { AddressCheck, Balance, BalancePoint, ElectrumServer, FeeRates, PriceRates, ReceiveAddress, Transaction, TransactionDetails, Utxo, WalletSnapshot } from '../types/wallet';

const BASE = '/api/wallet';

/** Endpoint definitions only: no Vue state or component lifecycle. */
export const walletApi = {
  server: (options?: RequestOptions) => requestJson<ElectrumServer>(`${BASE}/server`, options),
  prices: (options?: RequestOptions) => requestJson<PriceRates>(`${BASE}/prices`, options),
  fees: (options?: RequestOptions) => requestJson<FeeRates>(`${BASE}/fees`, options),
  balanceHistory: (options?: RequestOptions) => requestJson<BalancePoint[]>(`${BASE}/balance-history`, options),
  snapshot: (options?: RequestOptions) => requestJson<WalletSnapshot>(BASE, options),
  balance: (options?: RequestOptions) => requestJson<Balance>(`${BASE}/balance`, options),
  transactions: (options?: RequestOptions) => requestJson<Transaction[]>(`${BASE}/transactions`, options),
  transactionDetails: (txid: string, options?: RequestOptions) => requestJson<TransactionDetails>(`${BASE}/transactions/${encodeURIComponent(txid)}`, options),
  utxos: (options?: RequestOptions) => requestJson<Utxo[]>(`${BASE}/utxos`, options),
  receive: (options?: RequestOptions) => requestJson<ReceiveAddress>(`${BASE}/receive`, options),
  receiveAt: (index: number, options?: RequestOptions) => requestJson<ReceiveAddress>(`${BASE}/receive/${index}`, options),
  verifyAddress: (address: string, options?: RequestOptions) => requestJson<AddressCheck>(`${BASE}/verify?address=${encodeURIComponent(address)}`, options),
  qrAtUrl: (index: number) => `${BASE}/receive/${index}/qr`,
};