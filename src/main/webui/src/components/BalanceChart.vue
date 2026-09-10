<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useTemplateRef, watch } from 'vue';
import { createChart, createSeriesMarkers, AreaSeries, LineSeries, ColorType, CrosshairMode, LineStyle, type IChartApi, type ISeriesApi, type ISeriesMarkersPluginApi, type MouseEventParams, type Time, type AreaData, type BusinessDay } from 'lightweight-charts';
import { currencyLabel, type BitcoinUnit, type FiatCurrency } from '../currency';
import { useBalanceHistory } from '../composables/useBalanceHistory';
import { usePrivacy } from '../composables/usePrivacy';
import type { BalancePoint, Transaction } from '../types/wallet';
import { groupChartTransactions, type ChartTransactionGroup } from '../utils/chartTransactions';
import { readStorage, writeStorage } from '../utils/storage.ts';
import UiIcon from './UiIcon.vue';
import { formatDate, transactionClasses, transactionLabels } from '../utils/format';

const props = defineProps<{
  currency: BitcoinUnit;
  fiatCurrency: FiatCurrency;
  amount: (sats: number, signed?: boolean) => string;
  transactions: readonly Transaction[];
}>();

const { history, loading, error, refresh } = useBalanceHistory();
const { hidden, conceal } = usePrivacy();
const container = useTemplateRef<HTMLDivElement>('container');
let chart: IChartApi | null = null;
let balanceSeries: ISeriesApi<'Area'> | null = null;
let valueSeries: ISeriesApi<'Line'> | null = null;
let priceSeries: ISeriesApi<'Line'> | null = null;
let transactionMarkers: ISeriesMarkersPluginApi<Time> | null = null;
const tooltip = ref<{ group: ChartTransactionGroup; left: number } | null>(null);
const transactionIcons = { received: 'receive', sent: 'send', self: 'transfer' } as const;
const transactionAmountClasses = { received: 'text-emerald-300', sent: 'text-rose-300', self: 'text-sky-300' } as const;
let resizeObserver: ResizeObserver | null = null;

type ChartPeriod = 'daily' | 'weekly' | 'monthly' | 'ytd' | 'all';
const periods = [
  { id: 'daily', label: 'Daily' },
  { id: 'weekly', label: 'Weekly' },
  { id: 'monthly', label: 'Monthly' },
  { id: 'ytd', label: 'YTD' },
  { id: 'all', label: 'All' },
] as const;
const PERIOD_KEY = 'wallet-viewer.chart-period';
function readPeriod(): ChartPeriod {
  const saved = readStorage(PERIOD_KEY);
  return periods.find(option => option.id === saved)?.id ?? 'all';
}
const period = ref<ChartPeriod>(readPeriod());
watch(period, value => writeStorage(PERIOD_KEY, value));
const DAY = 86_400;
const FIAT_COLOR = '#38bdf8';
const PRICE_COLOR = '#34d399';

const BALANCE_KEY = 'wallet-viewer.chart-balance';
const VALUE_KEY = 'wallet-viewer.chart-value';
const PRICE_KEY = 'wallet-viewer.chart-price';
function readFlag(key: string): boolean {
  return readStorage(key) !== '0';
}
const showBalance = ref(readFlag(BALANCE_KEY));
const showValue = ref(readFlag(VALUE_KEY));
const showPrice = ref(readFlag(PRICE_KEY));
watch(showBalance, value => {
  writeStorage(BALANCE_KEY, value ? '1' : '0');
  balanceSeries?.applyOptions({ visible: value });
  if (!value) tooltip.value = null;
});
watch(showValue, value => {
  writeStorage(VALUE_KEY, value ? '1' : '0');
  valueSeries?.applyOptions({ visible: value });
});
watch(showPrice, value => {
  writeStorage(PRICE_KEY, value ? '1' : '0');
  priceSeries?.applyOptions({ visible: value });
});

const enoughData = computed(() => history.value.length >= 2);
const last = computed<BalancePoint | null>(() => history.value.at(-1) ?? null);
const currentFiatLabel = computed(() => {
  const point = last.value;
  if (!point) return '';
  const value = props.fiatCurrency === 'EUR' ? point.valueEur : point.valueUsd;
  return value === null ? 'Fiat unavailable' : `${Math.round(value).toLocaleString('en-US')} ${props.fiatCurrency}`;
});

function btcFormat() {
  return props.currency === 'SATS'
    ? { type: 'custom' as const, minMove: 1, formatter: (v: number) => Math.round(v).toLocaleString('en-US') }
    : { type: 'custom' as const, minMove: 0.00000001, formatter: (v: number) => v.toFixed(8) };
}
const fiatFormat = { type: 'custom' as const, minMove: 1, formatter: (v: number) => Math.round(v).toLocaleString('en-US') };

function windowStart(firstDay: number): number {
  const nowDay = Math.floor(Date.now() / 1000 / DAY) * DAY;
  switch (period.value) {
    case 'daily': return nowDay - 29 * DAY;
    case 'weekly': return nowDay - 26 * 7 * DAY;
    case 'monthly': return nowDay - 365 * DAY;
    case 'ytd': return Math.floor(Date.UTC(new Date().getUTCFullYear(), 0, 1) / 1000 / DAY) * DAY;
    default: return firstDay;
  }
}

function bucketId(day: number): number {
  if (period.value === 'weekly') return Math.floor(day / (7 * DAY));
  if (period.value === 'monthly') { const d = new Date(day * 1000); return d.getUTCFullYear() * 12 + d.getUTCMonth(); }
  return day;
}

// Backend series is already daily and gap-filled; keep the last point of each bucket in the window.
const visibleHistory = computed(() => {
  const first = history.value[0];
  if (hidden.value || !first) return [];
  const start = Math.max(windowStart(first.time), first.time);
  return history.value.filter(point => point.time >= start);
});

function bucketed(): BalancePoint[] {
  const lastPerBucket = new Map(visibleHistory.value.map(point => [bucketId(point.time), point]));
  return [...lastPerBucket.values()].toSorted((left, right) => left.time - right.time);
}

function businessDay(seconds: number): BusinessDay {
  const d = new Date(seconds * 1000);
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
}

interface ChartPoint { time: BusinessDay; balanceSats: number; valueEur: number | null; valueUsd: number | null; priceEur: number | null; priceUsd: number | null; }
// Bucket once; each series updates independently so a unit toggle never redraws the other curve.
const points = computed<ChartPoint[]>(() => hidden.value ? [] : bucketed().map(p => ({
  time: businessDay(p.time), balanceSats: p.balanceSats, valueEur: p.valueEur, valueUsd: p.valueUsd,
  priceEur: p.priceEur, priceUsd: p.priceUsd,
})));

const transactionGroups = computed(() => groupChartTransactions(visibleHistory.value, props.transactions, bucketId));
const groupsByTime = computed(() => new Map(transactionGroups.value.map(group => [JSON.stringify(businessDay(group.time)), group])));

function renderMarkers(): void {
  tooltip.value = null;
  transactionMarkers?.setMarkers(transactionGroups.value.map(group => ({
    time: businessDay(group.time), position: 'inBar', shape: 'circle', color: '#fef3c7',
    id: `transactions-${group.time}`, size: 0.7,
  })));
}

function showTransactionTooltip(event: MouseEventParams): void {
  tooltip.value = null;
  if (hidden.value || !showBalance.value || !event.point || !event.time || !balanceSeries || !chart || !container.value) return;
  const group = groupsByTime.value.get(JSON.stringify(event.time));
  const data = event.seriesData.get(balanceSeries);
  if (!group || !data || !('value' in data)) return;
  const vertical = balanceSeries.priceToCoordinate(data.value);
  const horizontal = chart.timeScale().timeToCoordinate(event.time);
  if (vertical === null || horizontal === null || Math.abs(event.point.y - vertical) > 16 || Math.abs(event.point.x - horizontal) > 16) return;
  tooltip.value = { group, left: Math.max(0, Math.min(event.point.x + 16, container.value.clientWidth - 300)) };
}

function renderBalance(): void {
  if (!balanceSeries) return;
  const factor = props.currency === 'SATS' ? 1 : 1e-8;
  balanceSeries.applyOptions({ priceFormat: btcFormat() });
  balanceSeries.setData(points.value.map(point => ({ time: point.time, value: point.balanceSats * factor } satisfies AreaData)));
  renderMarkers();
}

function renderFiat(): void {
  valueSeries?.setData(points.value.map(point => {
    const value = props.fiatCurrency === 'EUR' ? point.valueEur : point.valueUsd;
    return value === null ? { time: point.time } : { time: point.time, value };
  }));
  priceSeries?.setData(points.value.map(point => {
    const value = props.fiatCurrency === 'EUR' ? point.priceEur : point.priceUsd;
    return value === null ? { time: point.time } : { time: point.time, value };
  }));
}

onMounted(() => {
  if (!container.value) return;
  chart = createChart(container.value, {
    autoSize: true,
    layout: { background: { type: ColorType.Solid, color: 'transparent' }, textColor: '#a8a29e', fontFamily: 'inherit', attributionLogo: false },
    grid: { vertLines: { color: '#242220' }, horzLines: { color: '#242220' } },
    rightPriceScale: { borderColor: '#363330' },
    leftPriceScale: { visible: true, borderColor: '#363330' },
    timeScale: { borderColor: '#363330', secondsVisible: false },
    crosshair: {
      mode: CrosshairMode.Magnet,
      vertLine: { color: '#57534e', style: LineStyle.Dashed, labelBackgroundColor: '#242220' },
      horzLine: { color: '#57534e', style: LineStyle.Dashed, labelBackgroundColor: '#242220' },
    },
    handleScale: false,
    handleScroll: false,
  });
  balanceSeries = chart.addSeries(AreaSeries, {
    lineColor: '#f7931a', topColor: 'rgba(247, 147, 26, 0.35)', bottomColor: 'rgba(247, 147, 26, 0)',
    lineWidth: 2, priceLineVisible: false, priceFormat: btcFormat(), priceScaleId: 'right', visible: showBalance.value,
  });
  valueSeries = chart.addSeries(LineSeries, {
    color: FIAT_COLOR, lineWidth: 2, priceLineVisible: false, priceFormat: fiatFormat, priceScaleId: 'left', visible: showValue.value,
  });
  priceSeries = chart.addSeries(LineSeries, {
    color: PRICE_COLOR, lineWidth: 2, lineStyle: LineStyle.Dashed, priceLineVisible: false,
    priceFormat: fiatFormat, priceScaleId: 'left', visible: showPrice.value,
  });
  transactionMarkers = createSeriesMarkers(balanceSeries, [], { zOrder: 'top' });
  chart.subscribeCrosshairMove(showTransactionTooltip);
  chart.subscribeClick(showTransactionTooltip);
  renderBalance();
  renderFiat();
  chart.timeScale().fitContent();
  // The panel mounts inside a hidden tab (width 0); refit the content once the container gains size.
  resizeObserver = new ResizeObserver(() => {
    tooltip.value = null;
    if ((container.value?.clientWidth ?? 0) > 0) chart?.timeScale().fitContent();
  });
  resizeObserver.observe(container.value);
});

// Split watchers: BTC/SAT touches only the balance curve, EUR/USD the fiat curves.
watch([points, () => props.currency], renderBalance);
watch(transactionGroups, renderMarkers);
watch([points, () => props.fiatCurrency], renderFiat);
watch(points, () => chart?.timeScale().fitContent());
onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  chart?.unsubscribeCrosshairMove(showTransactionTooltip);
  chart?.unsubscribeClick(showTransactionTooltip);
  transactionMarkers?.detach();
  transactionMarkers = null;
  chart?.remove();
  chart = null;
  balanceSeries = null;
  valueSeries = null;
  priceSeries = null;
});
</script>

<template>
  <div class="wallet-panel p-5 sm:p-6">
    <div class="mb-4 flex flex-wrap items-baseline justify-between gap-3">
      <h2 class="section-title flex items-center gap-2"><UiIcon name="chart" class="text-accent" />Balance over time</h2>
      <p v-if="last" class="text-sm text-slate-300">
        <span class="sensitive font-semibold tabular-nums">{{ amount(last.balanceSats) }} {{ currencyLabel(currency) }}</span>
        <span class="sensitive tabular-nums" :style="{ color: FIAT_COLOR }"> · {{ conceal(currentFiatLabel) }}</span>
      </p>
    </div>
    <div v-show="enoughData" class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <div class="inline-flex flex-wrap rounded-xl border border-slate-700/60 bg-slate-950/40 p-0.5" role="group" aria-label="Chart period">
        <button v-for="option in periods" :key="option.id" type="button" :aria-pressed="period === option.id" class="rounded-lg px-2.5 py-1 text-xs font-semibold transition" :class="period === option.id ? 'bg-accent text-slate-950' : 'text-slate-400 hover:text-slate-200'" @click="period = option.id">{{ option.label }}</button>
      </div>
      <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
        <button type="button" :aria-pressed="showBalance" class="flex items-center gap-1.5 transition" :class="showBalance ? 'text-slate-300' : 'text-slate-600 line-through'" @click="showBalance = !showBalance">
          <span class="h-2 w-2 rounded-full bg-accent" :class="{ 'opacity-40': !showBalance }" aria-hidden="true" />Balance ({{ currencyLabel(currency) }})
        </button>
        <button type="button" :aria-pressed="showValue" class="flex items-center gap-1.5 transition" :class="showValue ? 'text-slate-300' : 'text-slate-600 line-through'" @click="showValue = !showValue">
          <span class="h-2 w-2 rounded-full" :class="{ 'opacity-40': !showValue }" :style="{ background: FIAT_COLOR }" aria-hidden="true" />Value ({{ fiatCurrency }})
        </button>
        <button type="button" :aria-pressed="showPrice" class="flex items-center gap-1.5 transition" :class="showPrice ? 'text-slate-300' : 'text-slate-600 line-through'" @click="showPrice = !showPrice">
          <span class="w-3 border-t-2 border-dashed" :class="{ 'opacity-40': !showPrice }" :style="{ borderColor: PRICE_COLOR }" aria-hidden="true" />BTC price ({{ fiatCurrency }})
        </button>
      </div>
    </div>
    <div v-if="error" role="status" class="mb-4 flex flex-wrap items-center gap-3 text-sm text-amber-300">
      <span>{{ history.length ? 'History update unavailable. Showing the last received data.' : 'Balance history unavailable.' }}</span>
      <button type="button" :disabled="loading" class="button-secondary inline-flex items-center gap-2 rounded-lg px-3" @click="refresh"><UiIcon name="refresh" />Retry</button>
    </div>
    <p v-else-if="last?.priceStale" role="status" class="mb-3 text-xs text-amber-300">Price history update unavailable. Using the last known quotes.</p>
    <p v-if="last?.priceTime != null" class="mb-3 text-xs text-slate-400">Latest historical quote: {{ formatDate(last.priceTime) }} · mempool.space</p>
    <p v-if="history.some(point => point.valueEur === null || point.valueUsd === null)" role="status" class="mb-3 text-xs text-amber-300">Fiat values are unavailable for part or all of this period. Bitcoin balances remain visible.</p>
    <p v-if="!enoughData && !error && !hidden" role="status" class="py-16 text-center text-sm text-slate-400">{{ loading ? 'Loading balance history...' : 'Not enough history to plot a curve yet.' }}</p>
    <p v-if="hidden" role="status" class="flex h-72 items-center justify-center text-sm text-slate-400">Amounts hidden</p>
    <div v-show="enoughData && !hidden" class="relative" @mouseleave="tooltip = null">
      <div ref="container" class="sensitive h-72 w-full" aria-hidden="true"></div>
      <div v-if="tooltip && !hidden && showBalance" role="tooltip" class="pointer-events-none absolute top-2 z-10 w-[300px] max-w-full overflow-hidden rounded-lg border border-slate-600/70 bg-slate-950/95 text-xs text-slate-200 shadow-xl backdrop-blur-sm" :style="{ left: `${tooltip.left}px` }">
        <p class="border-b border-slate-700/70 bg-slate-800/40 px-3 py-2 font-semibold">{{ tooltip.group.transactions.length }} {{ tooltip.group.transactions.length === 1 ? 'transaction' : 'transactions' }}</p>
        <ul class="divide-y divide-slate-700/60">
          <li v-for="transaction in tooltip.group.transactions.slice(0, 3)" :key="transaction.txid" class="px-3 py-2.5">
            <div class="flex items-center gap-2">
              <span class="flex h-7 w-7 shrink-0 items-center justify-center rounded-md border" :class="transactionClasses[transaction.type]">
                <UiIcon :name="transactionIcons[transaction.type]" />
              </span>
              <div class="flex min-w-0 flex-1 flex-wrap items-baseline justify-between gap-x-2 gap-y-0.5">
                <span class="font-medium" :class="transactionAmountClasses[transaction.type]">{{ transactionLabels[transaction.type] }}</span>
                <span class="break-all font-semibold tabular-nums" :class="transactionAmountClasses[transaction.type]">{{ amount(transaction.amount, true) }} {{ currencyLabel(currency) }}</span>
              </div>
            </div>
            <p class="mt-1.5 text-[11px] text-slate-400">{{ transaction.timestamp === null ? 'Date unavailable' : formatDate(transaction.timestamp) }}</p>
            <div class="mt-1 flex flex-wrap items-center justify-between gap-x-2 gap-y-1 text-[11px]">
              <span class="font-mono text-slate-500">{{ transaction.txid.slice(0, 10) }}...{{ transaction.txid.slice(-8) }}</span>
              <span class="inline-flex items-center gap-1" :class="transaction.confirmations > 0 ? 'text-slate-400' : 'text-amber-300'">
                <UiIcon v-if="transaction.confirmations > 0" name="check" class="h-3 w-3" />
                <span v-else class="h-1.5 w-1.5 rounded-full bg-amber-300" aria-hidden="true" />
                {{ transaction.confirmations > 0 ? 'Confirmed' : 'Pending' }}
              </span>
            </div>
          </li>
        </ul>
        <p v-if="tooltip.group.transactions.length > 3" class="border-t border-slate-700/70 px-3 py-2 text-[11px] text-slate-400">+{{ tooltip.group.transactions.length - 3 }} more transactions</p>
      </div>
    </div>
  </div>
</template>

