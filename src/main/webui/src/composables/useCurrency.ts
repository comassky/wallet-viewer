import { onMounted, onScopeDispose, ref, watch } from 'vue';
import { walletApi } from '@/services/walletApi';
import { usePrivacy } from '@/composables/usePrivacy.ts';
import type { PriceRates } from '@/types/wallet';
import { formatAmount, readDisplayPreferences, saveCurrency, saveFiatCurrency, validRates, type BitcoinUnit, type FiatCurrency } from '@/currency';

/** Display preference and fiat quote lifecycle, independent of wallet snapshot loading. */
export function useCurrency() {
  const { conceal } = usePrivacy();
  const preferences = readDisplayPreferences();
  const currency = ref<BitcoinUnit>(preferences.currency);
  const fiatCurrency = ref<FiatCurrency>(preferences.fiatCurrency);
  const rates = ref<PriceRates | null>(null);
  const ratesLoading = ref(false);
  const ratesError = ref(false);
  const controller = new AbortController();
  let timer: ReturnType<typeof setInterval> | undefined;

  async function refreshRates(): Promise<void> {
    if (controller.signal.aborted || ratesLoading.value) return;
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
  // Persist both on a change so a legacy USD preference survives a unit switch.
  watch([currency, fiatCurrency], () => saveFiatCurrency(fiatCurrency.value), { flush: 'sync' });
  onMounted(() => {
    void refreshRates();
    timer = setInterval(() => void refreshRates(), 60_000);
  });
  onScopeDispose(() => {
    controller.abort();
    clearInterval(timer);
  });

  const amount = (sats: number, signed = false): string => conceal(formatAmount(sats, currency.value, rates.value, signed));
  return { currency, fiatCurrency, rates, ratesLoading, ratesError, refreshRates, amount };
}