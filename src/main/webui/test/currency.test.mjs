import assert from 'node:assert/strict';
import test from 'node:test';
import { formatAmount, isCurrency, isFiat, validRates, currencyStorageKey, fiatCurrencyStorageKey, readDisplayPreferences, saveCurrency, saveFiatCurrency } from '../src/currency.ts';

const rates = { eur: 60_000, usd: 70_000, timestamp: 1_788_948_611 };
const normalize = value => value.replace(/,/g, '');

test('BTC retains eight decimals and SATS remains integer without a quote', () => {
  assert.equal(formatAmount(1, 'BTC', null), '0.00000001');
  assert.equal(normalize(formatAmount(123456789, 'SATS', null)), '123456789');
  assert.equal(formatAmount(123456789, 'SATS', null), '123,456,789');
  assert.equal(formatAmount(0, 'BTC', null), '0.00000000');
});

test('EUR and USD are computed directly from satoshis', () => {
  assert.equal(normalize(formatAmount(50_000_000, 'EUR', rates)), '30000.00');
  assert.equal(normalize(formatAmount(50_000_000, 'USD', rates)), '35000.00');
  assert.equal(formatAmount(0, 'EUR', rates), '0.00');
  assert.equal(formatAmount(100_000_000, 'USD', rates), '70,000.00');
});

test('transactions preserve signs in every unit', () => {
  assert.equal(formatAmount(-100_000_000, 'BTC', rates, true), '−1.00000000');
  assert.equal(formatAmount(1, 'SATS', rates, true), '+1');
  assert.equal(normalize(formatAmount(-100_000_000, 'USD', rates, true)), '−70000.00');
  assert.equal(normalize(formatAmount(100_000_000, 'EUR', rates, true)), '+60000.00');
});

test('missing or invalid rates never show a zero or a signed placeholder', () => {
  assert.equal(formatAmount(1, 'EUR', null), '—');
  assert.equal(formatAmount(-1, 'USD', null, true), '—');
  for (const invalid of [null, {}, { ...rates, eur: 0 }, { ...rates, usd: NaN }, { ...rates, usd: Infinity }]) {
    assert.equal(validRates(invalid), false);
  }
});

test('only supported stored currencies are accepted', () => {
  for (const unit of ['EUR', 'USD', 'BTC', 'SATS']) assert.equal(isCurrency(unit), true);
  for (const invalid of [null, 'GBP', 'eur', '']) assert.equal(isCurrency(invalid), false);
  assert.equal(isFiat('EUR'), true);
  assert.equal(isFiat('USD'), true);
  assert.equal(isFiat('BTC'), false);
  assert.equal(isFiat('SATS'), false);
});

test('display preferences migrate legacy fiat choices and save each unit independently', t => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  const store = new Map([[currencyStorageKey, 'USD']]);
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem: key => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, value),
  } });
  t.after(() => descriptor ? Object.defineProperty(globalThis, 'localStorage', descriptor) : delete globalThis.localStorage);
  assert.deepEqual(readDisplayPreferences(), { currency: 'BTC', fiatCurrency: 'USD' });
  saveFiatCurrency('USD');
  saveCurrency('SATS');
  assert.deepEqual(readDisplayPreferences(), { currency: 'SATS', fiatCurrency: 'USD' });
  saveFiatCurrency('EUR');
  assert.deepEqual(readDisplayPreferences(), { currency: 'SATS', fiatCurrency: 'EUR' });
  store.set(currencyStorageKey, 'SAT');
  assert.equal(readDisplayPreferences().currency, 'SATS');
  store.set(currencyStorageKey, 'invalid');
  store.set(fiatCurrencyStorageKey, 'GBP');
  assert.deepEqual(readDisplayPreferences(), { currency: 'BTC', fiatCurrency: 'EUR' });
});

test('display preferences still work when storage is blocked', t => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, get() { throw new Error('Blocked'); } });
  t.after(() => descriptor ? Object.defineProperty(globalThis, 'localStorage', descriptor) : delete globalThis.localStorage);
  assert.deepEqual(readDisplayPreferences(), { currency: 'BTC', fiatCurrency: 'EUR' });
  assert.doesNotThrow(() => saveFiatCurrency('USD'));
  assert.doesNotThrow(() => saveCurrency('SATS'));
});