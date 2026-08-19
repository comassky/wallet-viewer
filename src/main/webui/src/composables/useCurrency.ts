import { computed, onMounted, onScopeDispose, ref, watch } from 'vue';
import { walletApi } from '../services/walletApi';
import type { PriceRates } from '../types/wallet';
import { formatAmount, isFiat, readCurrency, saveCurrency, validRates, type Currency } from '../currency';

/** Display preference and fiat quote lifecycle, independent of wallet snapshot loading. */
export function useCurrency() {
  const currency = ref<Currency>(readCurrency());
  const rates = ref<PriceRates | null>(null);
  const ratesLoading = ref(false);
  const ratesError = ref(false);
  const fiat = computed(() => isFiat(currency.value));
  const controller = new AbortController();
  let timer: ReturnType<typeof setInterval> | undefined;

  async function refreshRates(): Promise<void> {
    if (controller.signal.aborted || !fiat.value || ratesLoading.value) return;
    ratesLoading.value = true;
    try {
      const result = await walletApi.prices({ signal: controller.signal });
      if (!validRates(result)) throw new Error('Invalid BTC quote');
      if (controller.signal.aborted) return;
      rates.value = result;
      ratesError.value = false;
    } catch {
      if (!controller.signal.aborted) ratesError.value = true;
    } finally {
      ratesLoading.value = false;
    }
  }

  watch(currency, saveCurrency, { flush: 'sync' });
  watch(currency, () => void refreshRates());
  onMounted(() => {
    void refreshRates();
    timer = setInterval(() => void refreshRates(), 60_000);
  });
  onScopeDispose(() => {
    controller.abort();
    clearInterval(timer);
  });

  const amount = (sats: number, signed = false): string => formatAmount(sats, currency.value, rates.value, signed);
  return { currency, rates, ratesLoading, ratesError, fiat, refreshRates, amount };
}