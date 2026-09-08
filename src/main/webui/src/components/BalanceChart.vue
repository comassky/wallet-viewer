<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { createChart, AreaSeries, ColorType, CrosshairMode, LineStyle, type IChartApi, type ISeriesApi, type AreaData, type BusinessDay } from 'lightweight-charts';
import type { Transaction } from '../types/wallet';
import { currencyLabel, type BitcoinUnit } from '../currency';
import UiIcon from './UiIcon.vue';

const props = defineProps<{
  transactions: Transaction[];
  currency: BitcoinUnit;
  amount: (sats: number, signed?: boolean) => string;
}>();

const container = ref<HTMLDivElement | null>(null);
let chart: IChartApi | null = null;
let series: ISeriesApi<'Area'> | null = null;

type ChartPeriod = 'daily' | 'weekly' | 'monthly' | 'ytd' | 'all';
const periods = [
  { id: 'daily', label: 'Daily' },
  { id: 'weekly', label: 'Weekly' },
  { id: 'monthly', label: 'Monthly' },
  { id: 'ytd', label: 'YTD' },
  { id: 'all', label: 'All' },
] as const;
const period = ref<ChartPeriod>('all');
const DAY = 86_400;

const enoughData = computed(() => props.transactions.length >= 2);
const totalSats = computed(() => props.transactions.reduce((sum, tx) => sum + tx.amount, 0));
const peakSats = computed(() => {
  const ordered = [...props.transactions].sort((a, b) => (a.timestamp ?? Infinity) - (b.timestamp ?? Infinity) || a.height - b.height);
  let running = 0, peak = 0;
  for (const tx of ordered) { running += tx.amount; peak = Math.max(peak, running); }
  return peak;
});

function priceFormat() {
  return props.currency === 'SATS'
    ? { type: 'custom' as const, minMove: 1, formatter: (v: number) => Math.round(v).toLocaleString('en-US') }
    : { type: 'custom' as const, minMove: 0.00000001, formatter: (v: number) => v.toFixed(8) };
}

// Full gap-filled daily cumulative balance, from the first transaction day to today.
function fullDaily(): { day: number; balance: number }[] {
  const ordered = [...props.transactions].sort((a, b) => (a.timestamp ?? Infinity) - (b.timestamp ?? Infinity) || a.height - b.height);
  const nowDay = Math.floor(Date.now() / 1000 / DAY) * DAY;
  const perDay = new Map<number, number>();
  let running = 0;
  for (const tx of ordered) {
    running += tx.amount;
    perDay.set(Math.floor((tx.timestamp ?? nowDay) / DAY) * DAY, running);
  }
  const keys = [...perDay.keys()].sort((a, b) => a - b);
  if (keys.length === 0) return [];
  const out: { day: number; balance: number }[] = [];
  let carried = 0;
  for (let day = keys[0]; day <= nowDay; day += DAY) {
    if (perDay.has(day)) carried = perDay.get(day)!;
    out.push({ day, balance: carried });
  }
  return out;
}

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

// One point per bucket (day/week/month), keeping the last day's cumulative balance in each bucket.
function buildData(): AreaData[] {
  const daily = fullDaily();
  if (daily.length === 0) return [];
  const start = Math.max(windowStart(daily[0].day), daily[0].day);
  const factor = props.currency === 'SATS' ? 1 : 1e-8;
  const lastPerBucket = new Map<number, { day: number; balance: number }>();
  for (const point of daily) if (point.day >= start) lastPerBucket.set(bucketId(point.day), point);
  return [...lastPerBucket.values()].sort((a, b) => a.day - b.day).map(point => {
    const d = new Date(point.day * 1000);
    const time: BusinessDay = { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
    return { time, value: point.balance * factor };
  });
}

function render() {
  if (!series) return;
  series.applyOptions({ priceFormat: priceFormat() });
  series.setData(buildData());
  chart?.timeScale().fitContent();
}

onMounted(() => {
  if (!container.value) return;
  chart = createChart(container.value, {
    autoSize: true,
    layout: { background: { type: ColorType.Solid, color: 'transparent' }, textColor: '#a8a29e', fontFamily: 'inherit', attributionLogo: false },
    grid: { vertLines: { color: '#242220' }, horzLines: { color: '#242220' } },
    rightPriceScale: { borderColor: '#363330' },
    timeScale: { borderColor: '#363330', secondsVisible: false },
    crosshair: {
      mode: CrosshairMode.Magnet,
      vertLine: { color: '#57534e', style: LineStyle.Dashed, labelBackgroundColor: '#242220' },
      horzLine: { color: '#57534e', style: LineStyle.Dashed, labelBackgroundColor: '#242220' },
    },
    handleScale: false,
    handleScroll: false,
  });
  series = chart.addSeries(AreaSeries, {
    lineColor: '#f7931a',
    topColor: 'rgba(247, 147, 26, 0.35)',
    bottomColor: 'rgba(247, 147, 26, 0)',
    lineWidth: 2,
    priceLineVisible: false,
    priceFormat: priceFormat(),
  });
  render();
});

watch([() => props.transactions, () => props.currency, () => period.value], render);
onBeforeUnmount(() => { chart?.remove(); chart = null; series = null; });
</script>

<template>
  <div class="wallet-panel p-5 sm:p-6">
    <div class="mb-4 flex flex-wrap items-baseline justify-between gap-3">
      <h2 class="section-title flex items-center gap-2"><UiIcon name="chart" class="text-accent" />Balance over time</h2>
      <p class="text-sm text-slate-300">Current <span class="sensitive font-semibold tabular-nums">{{ amount(totalSats) }} {{ currencyLabel(currency) }}</span> · Peak <span class="sensitive tabular-nums text-slate-400">{{ amount(peakSats) }}</span></p>
    </div>
    <div v-show="enoughData" class="mb-4 inline-flex flex-wrap rounded-xl border border-slate-700/60 bg-slate-950/40 p-0.5" role="group" aria-label="Chart period">
      <button v-for="option in periods" :key="option.id" type="button" :aria-pressed="period === option.id" class="rounded-lg px-2.5 py-1 text-xs font-semibold transition" :class="period === option.id ? 'bg-accent text-slate-950' : 'text-slate-400 hover:text-slate-200'" @click="period = option.id">{{ option.label }}</button>
    </div>
    <p v-show="!enoughData" class="py-16 text-center text-sm text-slate-400">Not enough history to plot a curve yet.</p>
    <div v-show="enoughData" ref="container" class="sensitive h-72 w-full"></div>
  </div>
</template>

