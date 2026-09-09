import type { PriceRates } from './types/wallet';
import { readStorage, writeStorage } from './utils/storage.ts';

export const currencies = ['EUR', 'USD', 'SATS', 'BTC'] as const;
export type Currency = typeof currencies[number];
export const currencyStorageKey = 'wallet-viewer.currency';
export const fiatCurrencyStorageKey = 'wallet-viewer.fiat-currency';
export type BitcoinUnit = 'BTC' | 'SATS';
export type FiatCurrency = 'EUR' | 'USD';

/** Migrate the previous single-unit preference without losing a chosen fiat currency. */
export function readDisplayPreferences(): { currency: BitcoinUnit; fiatCurrency: FiatCurrency } {
  const previous = readCurrency();
  const saved = readStorage(fiatCurrencyStorageKey);
  return {
    currency: previous === 'SATS' ? 'SATS' : 'BTC',
    fiatCurrency: saved === 'EUR' || saved === 'USD' ? saved : previous === 'USD' ? 'USD' : 'EUR',
  };
}

export function saveFiatCurrency(currency: FiatCurrency): void {
  writeStorage(fiatCurrencyStorageKey, currency);
}

export function currencyLabel(currency: Currency): string {
  return currency === 'SATS' ? 'SAT' : currency;
}

export function readCurrency(): Currency {
  const saved = readStorage(currencyStorageKey);
  if (saved === 'SAT') return 'SATS';
  return isCurrency(saved) ? saved : 'BTC';
}

export function saveCurrency(currency: Currency): void {
  writeStorage(currencyStorageKey, currency);
}

export function isCurrency(value: unknown): value is Currency {
  return currencies.some(currency => currency === value);
}

export function isFiat(currency: Currency): boolean {
  return currency === 'EUR' || currency === 'USD';
}

export function validRates(rates: PriceRates): boolean {
  return rates != null && Number.isFinite(rates.eur) && rates.eur > 0
    && Number.isFinite(rates.usd) && rates.usd > 0
    && Number.isFinite(rates.timestamp) && rates.timestamp > 0;
}

const formatters = {
  BTC: new Intl.NumberFormat('en-US', { minimumFractionDigits: 8, maximumFractionDigits: 8 }),
  SATS: new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }),
  EUR: new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
  USD: new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
};

/** All source values remain integer satoshis; switching never changes wallet data. */
export function formatAmount(sats: number, currency: Currency, rates: PriceRates | null, signed = false): string {
  let value: number;
  if (currency === 'SATS') value = sats;
  else if (currency === 'BTC') value = sats / 1e8;
  else {
    if (!rates || !validRates(rates)) return '—';
    value = sats / 1e8 * (currency === 'EUR' ? rates.eur : rates.usd);
  }
  if (signed) {
    return `${sats < 0 ? '−' : '+'}${formatters[currency].format(Math.abs(value))}`;
  }
  return formatters[currency].format(value);
}