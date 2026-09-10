import assert from 'node:assert/strict';
import test from 'node:test';
import { effectScope, nextTick, ref } from 'vue';
import { useIncomingNotifications } from '../src/composables/useIncomingNotifications.ts';
import { useToasts } from '../src/composables/useToast.ts';

test('notifications never disclose amounts and initial history is silent', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const scope = effectScope();
  t.after(() => scope.stop());
  const data = ref(null);
  const { toasts } = useToasts();
  scope.run(() => useIncomingNotifications(data));
  data.value = { transactions: [{ txid: 'existing', type: 'received', amount: 123456 }] };
  await nextTick();
  assert.equal(toasts.value.length, 0);
  data.value = { transactions: [...data.value.transactions, { txid: 'new', type: 'received', amount: 987654 }] };
  await nextTick();
  assert.deepEqual(toasts.value.map(toast => toast.message), ['Incoming payment']);
  t.mock.timers.tick(4000);
  assert.equal(toasts.value.length, 0);
});