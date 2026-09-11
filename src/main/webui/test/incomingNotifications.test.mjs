import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import * as Vue from 'vue';
import { compile } from '@vue/compiler-dom';
import { parse } from '@vue/compiler-sfc';
import { renderToString } from '@vue/server-renderer';
import { effectScope, nextTick, ref } from 'vue';
import { useIncomingNotifications } from '../src/composables/useIncomingNotifications.ts';
import { useToasts } from '../src/composables/useToast.ts';

test('reconnection toast has a spinner, coexists with action feedback and disappears after reconnecting', async () => {
  const source = readFileSync(new URL('../src/components/ToastHost.vue', import.meta.url), 'utf8');
  const { descriptor } = parse(source);
  const { code } = compile(descriptor.template.content, { mode: 'function', prefixIdentifiers: true });
  const component = {
    props: ['reconnecting', 'toasts'],
    components: { UiIcon: { render: () => null } },
    render: new Function('Vue', code)(Vue),
  };
  for (const reconnecting of [false, true, true, false]) {
    const html = await renderToString(Vue.createSSRApp(component, {
      reconnecting, toasts: [{ id: 1, message: 'Address copied' }],
    }));
    assert.equal((html.match(/Reconnecting to wallet/g) ?? []).length, reconnecting ? 1 : 0);
    assert.equal(html.includes('animate-spin'), reconnecting);
    assert.ok(html.includes('Address copied'));
    if (reconnecting) assert.ok(html.includes('motion-reduce:animate-none'));
  }
});

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
  assert.deepEqual(toasts.value.map(toast => toast.message), ['Incoming transaction']);
  data.value = { transactions: [...data.value.transactions, { txid: 'sent', type: 'sent', amount: -123456 }] };
  await nextTick();
  assert.deepEqual(toasts.value.map(toast => toast.message), ['Incoming transaction', 'Outgoing transaction']);
  t.mock.timers.tick(4000);
  assert.equal(toasts.value.length, 0);
});