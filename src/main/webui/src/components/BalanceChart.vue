<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { createChart, AreaSeries, LineSeries, ColorType, CrosshairMode, LineStyle, type IChartApi, type ISeriesApi, type AreaData, type LineData, type BusinessDay } from 'lightweight-charts';
import { currencyLabel, type BitcoinUnit, type FiatCurrency } from '../currency';
import { useBalanceHistory } from '../composables/useBalanceHistory';
import type { BalancePoint } from '../types/wallet';
import UiIcon from './UiIcon.vue';

const props = defineProps<{
  currency: BitcoinUnit;
  fiatCurrency: FiatCurrency;
  amount: (sats: number, signed?: boolean) => string;
}>();

const { history, error } = useBalanceHistory();
const container = ref<HTMLDivElement | null>(null);
let chart: IChartApi | null = null;
let balanceSeries: ISeriesApi<'Area'> | null = null;
let valueSeries: ISeriesApi<'Line'> | null = null;

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
  try {
    const saved = localStorage.getItem(PERIOD_KEY);
    if (saved && periods.some(option => option.id === saved)) return saved as ChartPeriod;
  } catch { /* Storage is optional. */ }
  return 'all';
}
const period = ref<ChartPeriod>(readPeriod());
watch(period, value => { try { localStorage.setItem(PERIOD_KEY, value); } catch { /* Storage is optional. */ } });
const DAY = 86_400;
const FIAT_COLOR = '#38bdf8';

const enoughData = computed(() => history.value.length >= 2);
const last = computed<BalancePoint | null>(() => history.value.at(-1) ?? null);
const currentFiatLabel = computed(() => {
  const point = last.value;
  if (!point) return '';
  const value = props.fiatCurrency === 'EUR' ? point.valueEur : point.valueUsd;
  return `${Math.round(value).toLocaleString('en-US')} ${props.fiatCurrency}`;
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
function bucketed(): BalancePoint[] {
  const points = history.value;
  if (points.length === 0) return [];
  const start = Math.max(windowStart(points[0].time), points[0].time);
  const lastPerBucket = new Map<number, BalancePoint>();
  for (const point of points) if (point.time >= start) lastPerBucket.set(bucketId(point.time), point);
  return [...lastPerBucket.values()].sort((a, b) => a.time - b.time);
}

function businessDay(seconds: number): BusinessDay {
  const d = new Date(seconds * 1000);
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
}

interface ChartPoint { time: BusinessDay; balanceSats: number; valueEur: number; valueUsd: number; }
// Bucket once; each series updates independently so a unit toggle never redraws the other curve.
const points = computed<ChartPoint[]>(() => bucketed().map(p => ({
  time: businessDay(p.time), balanceSats: p.balanceSats, valueEur: p.valueEur, valueUsd: p.valueUsd,
})));

function renderBalance(): void {
  if (!balanceSeries) return;
  const factor = props.currency === 'SATS' ? 1 : 1e-8;
  balanceSeries.applyOptions({ priceFormat: btcFormat() });
  balanceSeries.setData(points.value.map(p => ({ time: p.time, value: p.balanceSats * factor } as AreaData)));
}

function renderFiat(): void {
  if (!valueSeries) return;
  valueSeries.setData(points.value.map(p => ({ time: p.time, value: props.fiatCurrency === 'EUR' ? p.valueEur : p.valueUsd } as LineData)));
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
    lineWidth: 2, priceLineVisible: false, priceFormat: btcFormat(), priceScaleId: 'right',
  });
  valueSeries = chart.addSeries(LineSeries, {
    color: FIAT_COLOR, lineWidth: 2, priceLineVisible: false, priceFormat: fiatFormat, priceScaleId: 'left',
  });
  renderBalance();
  renderFiat();
  chart.timeScale().fitContent();
});

// Split watchers: BTC/SAT touches only the balance curve, EUR/USD only the value curve.
watch([points, () => props.currency], renderBalance);
watch([points, () => props.fiatCurrency], renderFiat);
watch(points, () => chart?.timeScale().fitContent());
onBeforeUnmount(() => { chart?.remove(); chart = null; balanceSeries = null; valueSeries = null; });
</script>

<template>
  <div class="wallet-panel p-5 sm:p-6">
    <div class="mb-4 flex flex-wrap items-baseline justify-between gap-3">
      <h2 class="section-title flex items-center gap-2"><UiIcon name="chart" class="text-accent" />Balance over time</h2>
      <p v-if="last" class="text-sm text-slate-300">
        <span class="sensitive font-semibold tabular-nums">{{ amount(last.balanceSats) }} {{ currencyLabel(currency) }}</span>
        <span class="sensitive tabular-nums" :style="{ color: FIAT_COLOR }"> · {{ currentFiatLabel }}</span>
      </p>
    </div>
    <div v-show="enoughData" class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <div class="inline-flex flex-wrap rounded-xl border border-slate-700/60 bg-slate-950/40 p-0.5" role="group" aria-label="Chart period">
        <button v-for="option in periods" :key="option.id" type="button" :aria-pressed="period === option.id" class="rounded-lg px-2.5 py-1 text-xs font-semibold transition" :class="period === option.id ? 'bg-accent text-slate-950' : 'text-slate-400 hover:text-slate-200'" @click="period = option.id">{{ option.label }}</button>
      </div>
      <div class="flex items-center gap-4 text-xs text-slate-400">
        <span class="flex items-center gap-1.5"><span class="h-2 w-2 rounded-full bg-accent" aria-hidden="true" />Balance ({{ currencyLabel(currency) }})</span>
        <span class="flex items-center gap-1.5"><span class="h-2 w-2 rounded-full" :style="{ background: FIAT_COLOR }" aria-hidden="true" />Value ({{ fiatCurrency }})</span>
      </div>
    </div>
    <p v-show="!enoughData" class="py-16 text-center text-sm" :class="error ? 'text-amber-300' : 'text-slate-400'">{{ error ? 'Balance history unavailable.' : 'Not enough history to plot a curve yet.' }}</p>
    <div v-show="enoughData" ref="container" class="sensitive h-72 w-full"></div>
  </div>
</template>

