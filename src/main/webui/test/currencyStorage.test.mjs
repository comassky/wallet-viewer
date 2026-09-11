import assert from 'node:assert/strict';
import test from 'node:test';
import { currencies, currencyLabel, currencyStorageKey, readCurrency, saveCurrency } from '@/currency.ts';
import { usePrivacy } from '@/composables/usePrivacy.ts';
import { computed } from 'vue';

test('privacy replaces values reactively and persists without requiring a DOM', t => {
  const saved = new Map();
  storage(t, { getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value) });
  const { hidden, conceal, toggle } = usePrivacy();
  const displayed = computed(() => conceal('123.456 BTC'));
  assert.equal(hidden.value, false);
  assert.equal(displayed.value, '123.456 BTC');
  toggle();
  assert.equal(displayed.value, 'Hidden');
  assert.equal(saved.get('wallet-viewer.privacy'), '1');
  toggle();
  assert.equal(displayed.value, '123.456 BTC');
});

function storage(t, value) {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value });
  t.after(() => {
    if (descriptor) Object.defineProperty(globalThis, 'localStorage', descriptor);
    else delete globalThis.localStorage;
  });
}

test('every selected currency survives a new preference read', t => {
  const saved = new Map();
  storage(t, { getItem: key => saved.get(key) ?? null, setItem: (key, value) => saved.set(key, value) });
  assert.equal(readCurrency(), 'BTC');
  for (const currency of currencies) {
    saveCurrency(currency);
    assert.equal(saved.get(currencyStorageKey), currency);
    assert.equal(readCurrency(), currency);
  }
  assert.equal(currencyLabel('SATS'), 'SAT');
});

test('invalid stored values fall back and SAT alias preserves previous preference', t => {
  let saved = 'GBP';
  storage(t, { getItem: () => saved });
  assert.equal(readCurrency(), 'BTC');
  saved = 'SAT';
  assert.equal(readCurrency(), 'SATS');
});

test('storage denial never breaks the dashboard', t => {
  storage(t, { getItem() { throw new Error('denied'); }, setItem() { throw new Error('quota'); } });
  assert.equal(readCurrency(), 'BTC');
  assert.doesNotThrow(() => saveCurrency('EUR'));
});