import assert from 'node:assert/strict';
import test from 'node:test';
import { effectScope } from 'vue';
import { useClipboard } from '@/composables/useClipboard.ts';

function fixture(t, writeText) {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { clipboard: { writeText } } });
  const scope = effectScope();
  t.after(() => {
    scope.stop();
    if (descriptor) Object.defineProperty(globalThis, 'navigator', descriptor);
    else delete globalThis.navigator;
  });
  return { scope, clipboard: scope.run(() => useClipboard()) };
}

test('copy succeeds and reset clears feedback', async t => {
  let written;
  const { clipboard } = fixture(t, async text => { written = text; });
  await clipboard.copy('test-address');
  assert.equal(written, 'test-address');
  assert.equal(clipboard.copied.value, true);
  clipboard.reset();
  assert.equal(clipboard.copied.value, false);
  assert.equal(clipboard.error.value, null);
});

test('clipboard failures provide manual-copy feedback', async t => {
  const { clipboard } = fixture(t, async () => { throw new Error('denied'); });
  await clipboard.copy('test-address');
  assert.equal(clipboard.copied.value, false);
  assert.match(clipboard.error.value, /Select and copy the address manually/);
});

test('reset and scope disposal invalidate pending copy results', async t => {
  let finish;
  const { clipboard, scope } = fixture(t, () => new Promise(resolve => { finish = resolve; }));
  const first = clipboard.copy('old-address');
  clipboard.reset();
  finish();
  await first;
  assert.equal(clipboard.copied.value, false);
  const second = clipboard.copy('another-address');
  scope.stop();
  finish();
  await second;
  assert.equal(clipboard.copied.value, false);
});