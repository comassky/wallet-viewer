import { requestJson, type RequestOptions } from '@/services/http.ts';
import { arrayOf, validAddressCheck, validBalance, validBalancePoint, validFees, validPrices, validReceiveAddress, validServer, validSnapshot, validTransaction, validTransactionDetails, validUtxo, type Validator } from '@/services/walletValidation.ts';

const BASE = '/api/wallet';

async function requestValidated<T>(url: string, validate: Validator<T>, options?: RequestOptions): Promise<T> {
  const data = await requestJson(url, options);
  if (!validate(data)) throw new Error('The server returned invalid data. Please try again.');
  return data;
}

/** Validated endpoints only: no Vue state or component lifecycle. */
export const walletApi = {
  server: (options?: RequestOptions) => requestValidated(`${BASE}/server`, validServer, options),
  prices: (options?: RequestOptions) => requestValidated(`${BASE}/prices`, validPrices, options),
  fees: (options?: RequestOptions) => requestValidated(`${BASE}/fees`, validFees, options),
  balanceHistory: (options?: RequestOptions) => requestValidated(`${BASE}/balance-history`, arrayOf(validBalancePoint), options),
  snapshot: (options?: RequestOptions) => requestValidated(BASE, validSnapshot, options),
  balance: (options?: RequestOptions) => requestValidated(`${BASE}/balance`, validBalance, options),
  transactions: (options?: RequestOptions) => requestValidated(`${BASE}/transactions`, arrayOf(validTransaction), options),
  transactionDetails: (txid: string, options?: RequestOptions) => requestValidated(`${BASE}/transactions/${encodeURIComponent(txid)}`, validTransactionDetails, options),
  utxos: (options?: RequestOptions) => requestValidated(`${BASE}/utxos`, arrayOf(validUtxo), options),
  receive: (options?: RequestOptions) => requestValidated(`${BASE}/receive`, validReceiveAddress, options),
  receiveAt: (index: number, options?: RequestOptions) => requestValidated(`${BASE}/receive/${index}`, validReceiveAddress, options),
  verifyAddress: (address: string, options?: RequestOptions) => requestValidated(`${BASE}/verify?address=${encodeURIComponent(address)}`, validAddressCheck, options),
  qrAtUrl: (index: number) => `${BASE}/receive/${index}/qr`,
};