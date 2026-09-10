import assert from 'node:assert/strict';
import test from 'node:test';
import { computed, createRenderer, ref } from 'vue';
import { useMediaQuery } from '../src/composables/useMediaQuery.ts';
import { useRovingTabs } from '../src/composables/useRovingTabs.ts';

test('media query tracks viewport changes and removes its listener on unmount', context => {
  const listeners = new Set();
  const media = {
    matches: false,
    addEventListener(event, listener) { assert.equal(event, 'change'); listeners.add(listener); },
    removeEventListener(event, listener) { assert.equal(event, 'change'); listeners.delete(listener); },
  };
  const previousWindow = globalThis.window;
  globalThis.window = { matchMedia(query) { assert.equal(query, '(min-width: 768px)'); return media; } };
  context.after(() => {
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
  });
  const renderer = createRenderer({
    createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null,
  });
  let matches;
  const app = renderer.createApp({ setup() { matches = useMediaQuery('(min-width: 768px)'); return () => null; } });
  app.mount({});
  context.after(() => app.unmount());
  assert.equal(matches.value, false);
  assert.equal(listeners.size, 1);
  media.matches = true;
  listeners.forEach(listener => listener());
  assert.equal(matches.value, true);
  media.matches = false;
  listeners.forEach(listener => listener());
  assert.equal(matches.value, false);
  app.unmount();
  assert.equal(listeners.size, 0);
});

test('roving tabs use the currently available views and focus the matching button', () => {
  const showCharts = ref(true);
  const ids = computed(() => showCharts.value ? ['activity', 'utxos', 'chart'] : ['activity', 'utxos']);
  let focused;
  const buttons = ref(['chart', 'activity', 'utxos'].map(id => ({
    id: `wallet-tab-${id}`, focus() { focused = id; },
  })));
  const { activeTab, onKeydown } = useRovingTabs(ids, 'activity', buttons);
  function press(key, index) {
    let prevented = false;
    onKeydown({ key, preventDefault() { prevented = true; } }, index);
    return prevented;
  }
  assert.equal(press('End', 0), true);
  assert.equal(activeTab.value, 'chart');
  assert.equal(focused, 'chart');
  showCharts.value = false;
  press('End', 0);
  assert.equal(activeTab.value, 'utxos');
  assert.equal(focused, 'utxos');
  press('ArrowRight', 1);
  assert.equal(activeTab.value, 'activity');
  press('ArrowLeft', 0);
  assert.equal(activeTab.value, 'utxos');
  press('Home', 1);
  assert.equal(activeTab.value, 'activity');
  assert.equal(press('Tab', 0), false);
  const emptyTabs = useRovingTabs([], 'activity', buttons);
  let prevented = false;
  emptyTabs.onKeydown({ key: 'ArrowRight', preventDefault() { prevented = true; } }, 0);
  assert.equal(emptyTabs.activeTab.value, 'activity');
  assert.equal(prevented, false);
  assert.equal(press('ArrowRight', Number.NaN), false);
  assert.equal(activeTab.value, 'activity');
});