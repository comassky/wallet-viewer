import assert from 'node:assert/strict';
import test from 'node:test';
import { createRenderer, isProxy } from 'vue';
import { usePolledResource } from '../src/composables/usePolledResource.ts';

function fixture(context, fetcher, initial = []) {
  const renderer = createRenderer({
    createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null,
  });
  let state;
  const app = renderer.createApp({
    setup() { state = usePolledResource(fetcher, initial, 1000); return () => null; },
  });
  app.mount({});
  context.after(() => app.unmount());
  return { app, state };
}

test('polling keeps raw snapshot identity and replaces it after refresh', async context => {
  const first = [{ time: 1, nested: { value: 42 } }];
  let payload = first;
  const { state } = fixture(context, async () => payload);
  await Promise.resolve();
  assert.equal(state.data.value, first);
  assert.equal(isProxy(state.data.value[0].nested), false);
  payload = [{ time: 2, nested: { value: 43 } }];
  await state.refresh();
  assert.equal(state.data.value, payload);
  assert.equal(state.error.value, false);
});

test('failed refresh retains the previous snapshot and can recover', async context => {
  const snapshot = [{ time: 1 }];
  let failing = false;
  const { state } = fixture(context, async () => {
    if (failing) throw new Error('Invalid API data');
    return snapshot;
  });
  await Promise.resolve();
  failing = true;
  await state.refresh();
  assert.equal(state.data.value, snapshot);
  assert.equal(state.error.value, true);
  assert.equal(state.loading.value, false);
  failing = false;
  await state.refresh();
  assert.equal(state.error.value, false);
});

test('polling coalesces overlapping requests and disposal rejects late results', async context => {
  context.mock.timers.enable({ apis: ['setInterval'] });
  const request = Promise.withResolvers();
  let signal;
  let calls = 0;
  const initial = [];
  const { app, state } = fixture(context, options => {
    signal = options.signal;
    calls++;
    return request.promise;
  }, initial);
  context.mock.timers.tick(3000);
  await state.refresh();
  assert.equal(calls, 1);
  app.unmount();
  assert.equal(signal.aborted, true);
  request.resolve([{ time: 1 }]);
  await Promise.resolve();
  assert.equal(state.data.value, initial);
  context.mock.timers.tick(3000);
  await state.refresh();
  assert.equal(calls, 1);
  assert.equal(state.error.value, false);
});