import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import * as Vue from 'vue';
import { compileScript, parse } from '@vue/compiler-sfc';
import ts from 'typescript';
import { groupChartTransactions } from '@/utils/chartTransactions.ts';

const day = 86_400;
const point = time => ({ time, balanceSats: 100, valueEur: null, valueUsd: null });
const transaction = (txid, timestamp) => ({ txid, timestamp });

test('BTC price series follows fiat currency, visibility and privacy independently of the wallet balance', async context => {
  const history = Vue.ref([
    { ...point(day), balanceSats: 0, priceEur: null, priceUsd: null },
    { ...point(day * 2), balanceSats: 0, priceEur: 60000, priceUsd: 70000 },
  ]);
  const hidden = Vue.ref(false);
  const storage = new Map();
  const series = [];
  let removed = false;
  const chart = {
    addSeries(kind, options) {
      const instance = { options, data: [], applyOptions(value) { Object.assign(this.options, value); }, setData(value) { this.data = value; } };
      series.push(instance);
      return instance;
    },
    subscribeCrosshairMove() {}, subscribeClick() {}, unsubscribeCrosshairMove() {}, unsubscribeClick() {},
    timeScale: () => ({ fitContent() {} }), remove() { removed = true; },
  };
  const imports = {
    vue: Vue,
    'lightweight-charts': {
      createChart: () => chart, createSeriesMarkers: () => ({ setMarkers() {}, detach() {} }),
      AreaSeries: {}, LineSeries: {}, ColorType: { Solid: 'solid' }, CrosshairMode: { Magnet: 1 }, LineStyle: { Dashed: 2 },
    },
    '@/currency': {},
    '@/composables/useBalanceHistory': { useBalanceHistory: () => ({ history, loading: Vue.ref(false), error: Vue.ref(null), refresh() {} }) },
    '@/composables/usePrivacy': { usePrivacy: () => ({ hidden, conceal: value => value }) },
    '@/utils/chartTransactions': { groupChartTransactions },
    '@/utils/storage.ts': { readStorage: key => storage.get(key) ?? null, writeStorage: (key, value) => storage.set(key, value) },
    '@/components/UiIcon.vue': {}, '@/utils/format': {},
  };
  const source = readFileSync(new URL('../src/components/BalanceChart.vue', import.meta.url), 'utf8');
  const { descriptor } = parse(source);
  const compiled = compileScript(descriptor, { id: 'chart-test' });
  const { outputText } = ts.transpileModule(compiled.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ESNext } });
  const exports = {};
  new Function('require', 'exports', outputText)(name => {
    assert.ok(name in imports, `Unexpected import: ${name}`);
    return imports[name];
  }, exports);
  const previousObserver = globalThis.ResizeObserver;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} };
  context.after(() => {
    if (previousObserver === undefined) delete globalThis.ResizeObserver;
    else globalThis.ResizeObserver = previousObserver;
  });
  const renderer = Vue.createRenderer({
    createElement: () => ({ clientWidth: 900 }), insert() {}, remove() {}, patchProp() {},
    parentNode: () => null, nextSibling: () => null,
  });
  const props = Vue.reactive({ currency: 'BTC', fiatCurrency: 'USD', amount: String, transactions: [] });
  let instance;
  const component = { ...exports.default, render() { return Vue.h('div', { ref: 'container' }); } };
  const app = renderer.createApp({ render: () => Vue.h(component, { ...props, ref: value => { instance = value; } }) });
  app.mount({});
  context.after(() => app.unmount());
  assert.equal(series.length, 3);
  const price = series[2];
  assert.equal(price.options.priceScaleId, 'left');
  assert.equal(price.options.visible, false);
  instance.$.setupState.showPrice = true;
  await Vue.nextTick();
  assert.equal(price.options.visible, true);
  assert.equal(storage.get('wallet-viewer.chart-price'), '1');
  assert.deepEqual(price.data.map(entry => entry.value), [undefined, 70000]);
  props.fiatCurrency = 'EUR';
  await Vue.nextTick();
  assert.deepEqual(price.data.map(entry => entry.value), [undefined, 60000]);
  props.currency = 'SATS';
  await Vue.nextTick();
  assert.equal(price.data[1].value, 60000);
  instance.$.setupState.showPrice = false;
  await Vue.nextTick();
  assert.equal(price.options.visible, false);
  assert.equal(storage.get('wallet-viewer.chart-price'), '0');
  hidden.value = true;
  await Vue.nextTick();
  assert.deepEqual(price.data, []);
  app.unmount();
  assert.equal(removed, true);
});

test('daily markers group transactions without adding points on inactive days', () => {
  const transactions = [transaction('older', day + 1), transaction('newer', day + 2)];
  assert.deepEqual(groupChartTransactions([point(day), point(day * 2)], transactions, time => time), [
    { time: day, transactions: [transactions[1], transactions[0]] },
  ]);
  assert.equal(transactions[0].txid, 'older');
});

test('weekly markers use the final plotted day and exclude dates outside the visible window', () => {
  const transactions = [transaction('outside', day), transaction('first', day * 2), transaction('last', day * 3)];
  assert.deepEqual(groupChartTransactions([point(day * 2), point(day * 3)], transactions, time => Math.floor(time / (7 * day))), [
    { time: day * 3, transactions: [transactions[2], transactions[1]] },
  ]);
});

test('monthly markers keep month and year boundaries separate', () => {
  const december = Date.UTC(2025, 11, 31) / 1000;
  const january = Date.UTC(2026, 0, 1) / 1000;
  const transactions = [transaction('dec', december), transaction('jan', january)];
  const groups = groupChartTransactions([point(december), point(january)], transactions, time => {
    const date = new Date(time * 1000);
    return date.getUTCFullYear() * 12 + date.getUTCMonth();
  });
  assert.deepEqual(groups.map(group => group.transactions.map(tx => tx.txid)), [['dec'], ['jan']]);
});

test('pending transactions map to today only if today is in the history', () => {
  const pending = transaction('pending', null);
  assert.deepEqual(groupChartTransactions([point(day * 2)], [pending], time => time, day * 2 + 15), [
    { time: day * 2, transactions: [pending] },
  ]);
  assert.deepEqual(groupChartTransactions([point(day)], [pending], time => time, day * 2 + 15), []);
});

test('empty or concealed history produces no transaction markers', () => {
  assert.deepEqual(groupChartTransactions([], [transaction('hidden', day)], time => time), []);
  assert.deepEqual(groupChartTransactions([point(day)], [], time => time), []);
});