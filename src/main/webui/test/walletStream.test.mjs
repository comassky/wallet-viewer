import assert from 'node:assert/strict';
import test from 'node:test';
import { createRenderer } from 'vue';
import { createWalletStream, parseWalletEnvelope, walletStreamUrl } from '@/services/walletStream.ts';
import { useWallet } from '@/composables/useWallet.ts';

class FakeTimers {
  jobs = new Map();
  sequence = 0;
  setTimeout = (callback, delay) => {
    const id = ++this.sequence;
    this.jobs.set(id, { callback, delay });
    return id;
  };
  clearTimeout = id => { this.jobs.delete(id); };
  next() {
    assert.equal(this.jobs.size, 1, 'only one deadline or retry should be active');
    return this.jobs.values().next().value;
  }
  runNext() {
    const job = this.next();
    const id = this.jobs.keys().next().value;
    this.jobs.delete(id);
    job.callback();
  }
}

class FakeSocket {
  readyState = 0;
  onopen = null;
  onmessage = null;
  onerror = null;
  onclose = null;
  sent = [];
  closes = 0;
  open() { this.readyState = 1; this.onopen?.({}); }
  frame(value) { this.onmessage?.({ data: JSON.stringify(value) }); }
  end() { this.readyState = 3; this.onclose?.({}); }
  send(value) { this.sent.push(value); }
  close() { this.closes++; this.end(); }
}

function snapshot(total = 42) {
  return {
    balance: { confirmed: total, unconfirmed: 0, total },
    utxos: [{ txid: 'a'.repeat(64), vout: 0, value: total, height: 1, confirmations: 2, address: 'bc1ptest' }],
    transactions: [{ txid: 'a'.repeat(64), amount: total, received: total, sent: 0, height: 1, confirmations: 2, timestamp: null, type: 'received', addresses: ['bc1ptest'] }],
    receiveAddress: { index: 0, address: 'bc1ptest', path: "m/86'/0'/0'/0/0" },
  };
}

function envelope(version = 1, state = snapshot(), status = 'live', message = null) {
  return { version, status, message, snapshot: state };
}

function environment(overrides = {}) {
  const timers = new FakeTimers();
  const sockets = [];
  const updates = [];
  const options = {
    url: 'wss://wallet.example/api/wallet/live',
    scheduler: timers,
    random: () => 1,
    reconnectBaseMs: 100,
    reconnectMaxMs: 800,
    openTimeoutMs: 1000,
    firstFrameTimeoutMs: 2000,
    createSocket: url => {
      assert.equal(url, 'wss://wallet.example/api/wallet/live');
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    },
    onState: state => updates.push(state),
    ...overrides,
  };
  return { timers, sockets, updates, options };
}

function setup(overrides) {
  const env = environment(overrides);
  return { ...env, stream: createWalletStream(env.options) };
}

test('same-origin URLs use ws/wss and preserve the development port', () => {
  assert.equal(walletStreamUrl({ protocol: 'https:', host: 'wallet.example' }), 'wss://wallet.example/api/wallet/live');
  assert.equal(walletStreamUrl({ protocol: 'http:', host: 'localhost:5173' }), 'ws://localhost:5173/api/wallet/live');
});

test('initial cache state and subsequent complete snapshots replace state without REST', t => {
  const fetch = t.mock.method(globalThis, 'fetch', () => assert.fail('Wallet streaming must never fetch REST state'));
  const { stream, sockets, timers } = setup();
  assert.equal(sockets.length, 0, 'factory construction is lazy');
  stream.start();
  stream.start();
  assert.equal(sockets.length, 1);
  sockets[0].open();
  sockets[0].frame(envelope(0, null, 'loading'));
  assert.equal(stream.getState().loading, true);
  assert.equal(stream.getState().data, null);
  assert.equal(timers.jobs.size, 0);

  const first = snapshot();
  sockets[0].frame(envelope(1, first));
  assert.deepEqual(stream.getState(), {
    data: first, updatedAt: null, loading: false, error: null, connection: 'connected', status: 'live', message: null,
  });
  const next = snapshot(99);
  next.transactions = [];
  next.utxos = [];
  next.receiveAddress = { index: 1, address: 'bc1pnext', path: "m/86'/0'/0'/0/1" };
  sockets[0].frame(envelope(2, next));
  assert.deepEqual(stream.getState().data, next, 'arrays and receive address are replaced, not patched');
  stream.refresh();
  assert.deepEqual(sockets[0].sent, ['refresh']);
  assert.equal(fetch.mock.callCount(), 0);
  assert.equal(timers.jobs.size, 0, 'no application heartbeat or polling for an unchanged wallet');
  stream.dispose();
});

test('syncing, offline and error preserve the last cache snapshot; recovery clears errors', () => {
  const { stream, sockets } = setup();
  stream.start();
  sockets[0].open();
  sockets[0].frame(envelope(1));
  const last = stream.getState().data;
  sockets[0].frame(envelope(2, null, 'syncing', 'Checking the chain'));
  assert.equal(stream.getState().data, last);
  assert.equal(stream.getState().status, 'syncing');
  assert.equal(stream.getState().loading, false);
  sockets[0].frame(envelope(3, null, 'offline', 'Electrum is unavailable'));
  assert.equal(stream.getState().connection, 'connected', 'backend availability differs from browser transport');
  assert.equal(stream.getState().data, last);
  assert.equal(stream.getState().error, 'Electrum is unavailable');
  assert.equal(stream.getState().message, 'Electrum is unavailable');
  sockets[0].frame(envelope(4, null, 'error'));
  assert.equal(stream.getState().data, last);
  assert.equal(stream.getState().error, 'Wallet synchronization failed.');
  sockets[0].frame(envelope(5, snapshot(100), 'offline', 'Cached while offline'));
  assert.equal(stream.getState().data.balance.total, 100, 'an offline envelope may itself supply the last successful cache');
  sockets[0].frame(envelope(6, snapshot(101)));
  assert.equal(stream.getState().status, 'live');
  assert.equal(stream.getState().error, null);
  assert.equal(stream.getState().message, null);
  stream.dispose();
});

test('initial backend errors stop loading and allow cache replay retry', () => {
  const { stream, sockets } = setup();
  stream.start();
  sockets[0].open();
  sockets[0].frame(envelope(0, null, 'offline'));
  assert.equal(stream.getState().data, null);
  assert.equal(stream.getState().loading, false);
  assert.equal(stream.getState().error, 'Wallet backend is offline.');
  stream.refresh();
  assert.deepEqual(sockets[0].sent, ['refresh']);
  assert.equal(sockets.length, 1);
  stream.dispose();
});

test('invalid envelopes and nested snapshots are ignored without throwing', () => {
  const invalid = [
    'not JSON', '', 'null', '[]', '{}', new Uint8Array([1]),
    JSON.stringify(envelope(-1)), JSON.stringify(envelope(1.5)),
    JSON.stringify(envelope(Number.MAX_SAFE_INTEGER + 1)),
    JSON.stringify({ ...envelope(), version: '1' }),
    JSON.stringify({ ...envelope(), status: ['live'] }),
    JSON.stringify({ ...envelope(), status: 'unknown' }),
    JSON.stringify({ ...envelope(), message: {} }),
    JSON.stringify({ ...envelope(), message: undefined }),
    JSON.stringify({ ...envelope(), snapshot: undefined }),
    JSON.stringify(envelope(1, {})),
    JSON.stringify(envelope(1, { ...snapshot(), balance: { total: 42 } })),
    JSON.stringify(envelope(1, { ...snapshot(), balance: { confirmed: null, unconfirmed: 0, total: 42 } })),
    JSON.stringify(envelope(1, { ...snapshot(), receiveAddress: { index: -1, address: 'x', path: 'x' } })),
    JSON.stringify(envelope(1, { ...snapshot(), transactions: [null] })),
    JSON.stringify(envelope(1, { ...snapshot(), transactions: [{ ...snapshot().transactions[0], type: 'invalid' }] })),
    JSON.stringify(envelope(1, { ...snapshot(), transactions: [{ ...snapshot().transactions[0], timestamp: 'today' }] })),
    JSON.stringify(envelope(1, { ...snapshot(), utxos: [{ ...snapshot().utxos[0], value: '42' }] })),
  ];
  for (const frame of invalid) assert.equal(parseWalletEnvelope(frame), null);
  const text = '<script>throw new Error("not executable")</script>';
  assert.equal(parseWalletEnvelope(JSON.stringify(envelope(1, null, 'error', text))).message, text);
});

test('duplicate/stale versions and malformed high versions cannot overwrite accepted state', () => {
  const { stream, sockets } = setup();
  stream.start();
  sockets[0].open();
  sockets[0].frame(envelope(10));
  const accepted = stream.getState();
  sockets[0].frame(envelope(10, snapshot(999), 'error', 'Duplicate'));
  sockets[0].frame(envelope(9, snapshot(999)));
  sockets[0].frame(envelope(1000, { broken: true }));
  sockets[0].onmessage({ data: '{invalid' });
  assert.equal(stream.getState(), accepted);
  sockets[0].frame(envelope(11, snapshot(43)));
  assert.equal(stream.getState().data.balance.total, 43);
  stream.dispose();
});

test('disconnect retains data; old socket events and deadlines cannot affect a new generation', () => {
  const { stream, sockets, timers } = setup();
  stream.start();
  const old = sockets[0];
  const oldDeadline = timers.next().callback;
  const queued = { open: old.onopen, message: old.onmessage, error: old.onerror, close: old.onclose };
  old.open();
  old.frame(envelope(500));
  const data = stream.getState().data;
  old.end();
  assert.equal(stream.getState().connection, 'reconnecting');
  assert.equal(stream.getState().status, 'offline');
  assert.equal(stream.getState().loading, false);
  assert.equal(stream.getState().data, data);
  assert.match(stream.getState().error, /Reconnecting/);
  timers.runNext();
  const reconnecting = stream.getState();
  queued.open({});
  queued.message({ data: JSON.stringify(envelope(999, snapshot(999))) });
  queued.error({});
  queued.close({});
  oldDeadline();
  assert.equal(stream.getState(), reconnecting);
  assert.equal(timers.next().delay, 1000, 'old events did not replace the new opening deadline');
  sockets[1].open();
  sockets[1].frame(envelope(0, snapshot(7)));
  assert.equal(stream.getState().data.balance.total, 7, 'version reset is accepted on a new connection');
  queued.message({ data: JSON.stringify(envelope(1000, snapshot(999))) });
  assert.equal(stream.getState().data.balance.total, 7);
  assert.equal(stream.getState().error, null);
  stream.dispose();
});

test('exponential backoff stays capped even if connections open but never supply a cache frame', () => {
  const { stream, sockets, timers } = setup();
  stream.start();
  for (const expected of [100, 200, 400, 800, 800, 800]) {
    sockets.at(-1).open();
    sockets.at(-1).onerror({});
    assert.equal(timers.next().delay, expected);
    timers.runNext();
    assert.equal(stream.getState().loading, false, 'automatic retries do not reintroduce an endless initial spinner');
  }
  sockets.at(-1).open();
  sockets.at(-1).frame(envelope(0));
  sockets.at(-1).end();
  assert.equal(timers.next().delay, 100, 'valid cache state resets backoff');
  stream.dispose();
});

test('reconnect jitter uses the injected random source within the configured cap', () => {
  for (const [random, expected] of [[0, 50], [0.5, 75], [1, 100]]) {
    const { stream, sockets, timers } = setup({ random: () => random });
    stream.start();
    sockets[0].end();
    assert.equal(timers.next().delay, expected);
    stream.dispose();
  }
});

test('opening timeout becomes a retryable error, and manual retry cancels backoff', () => {
  const { stream, sockets, timers } = setup();
  stream.start();
  assert.equal(timers.next().delay, 1000);
  timers.runNext();
  assert.equal(sockets[0].closes, 1);
  assert.equal(stream.getState().loading, false);
  assert.match(stream.getState().error, /did not open/);
  assert.equal(stream.getState().connection, 'reconnecting');
  stream.refresh();
  assert.equal(sockets.length, 2);
  assert.equal(timers.next().delay, 1000);
  stream.refresh();
  assert.equal(sockets.length, 2, 'refresh does not duplicate a pending connection');
  sockets[1].open();
  sockets[1].frame(envelope(0));
  assert.equal(timers.jobs.size, 0);
  assert.equal(stream.getState().status, 'live');
  stream.dispose();
});

test('an open socket that never supplies a valid first cache envelope times out', () => {
  const { stream, sockets, timers } = setup();
  stream.start();
  sockets[0].open();
  assert.equal(timers.next().delay, 2000);
  sockets[0].frame({ invalid: true });
  timers.runNext();
  assert.equal(stream.getState().loading, false);
  assert.match(stream.getState().error, /No wallet cache state/);
  assert.equal(stream.getState().connection, 'reconnecting');
  stream.dispose();
});

test('constructor and refresh send failures use the same reconnect path', () => {
  const failed = setup({ createSocket: () => { throw new Error('Unavailable'); } });
  assert.doesNotThrow(() => failed.stream.start());
  assert.equal(failed.stream.getState().loading, false);
  assert.match(failed.stream.getState().error, /Unable to connect/);
  assert.equal(failed.timers.next().delay, 100);
  failed.stream.dispose();

  const { stream, sockets, timers } = setup();
  stream.start();
  sockets[0].open();
  sockets[0].frame(envelope());
  sockets[0].send = () => { throw new Error('Closed'); };
  assert.doesNotThrow(() => stream.refresh());
  assert.match(stream.getState().error, /Unable to replay/);
  assert.equal(timers.next().delay, 100);
  stream.dispose();
});

for (const phase of ['opening', 'awaiting cache', 'connected', 'backoff']) {
  test(`dispose during ${phase} closes transport, cancels timers and forbids future activity`, () => {
    const { stream, sockets, timers, updates } = setup();
    stream.start();
    const old = sockets[0];
    const queued = { open: old.onopen, message: old.onmessage, error: old.onerror, close: old.onclose };
    if (phase !== 'opening') old.open();
    if (phase === 'connected') old.frame(envelope());
    if (phase === 'backoff') old.end();
    const pending = timers.jobs.size ? timers.next().callback : null;
    stream.dispose();
    const count = updates.length;
    assert.equal(stream.getState().connection, 'disconnected');
    assert.equal(stream.getState().loading, false);
    assert.equal(timers.jobs.size, 0);
    assert.equal(old.closes, 1);
    assert.equal(old.onmessage, null);
    queued.open({});
    queued.message({ data: JSON.stringify(envelope(999)) });
    queued.error({});
    queued.close({});
    pending?.();
    stream.refresh();
    stream.start();
    stream.dispose();
    assert.equal(updates.length, count);
    assert.equal(sockets.length, 1);
    assert.equal(timers.jobs.size, 0);
  });
}

test('useWallet mounts and disposes its stream with no DOM and no REST snapshot request', async t => {
  const fetch = t.mock.method(globalThis, 'fetch', () => assert.fail('useWallet must not fetch REST state'));
  const { sockets, timers, options } = environment();
  const renderer = createRenderer({
    patchProp() {}, insert() {}, remove() {},
    createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null,
  });
  let wallet;
  const app = renderer.createApp({
    setup() { wallet = useWallet(options); return () => null; },
  });
  app.mount({});
  let mounted = true;
  t.after(() => { if (mounted) app.unmount(); });
  assert.equal(sockets.length, 1);
  assert.equal(wallet.loading.value, true);
  sockets[0].open();
  sockets[0].frame(envelope());
  assert.deepEqual(wallet.data.value, snapshot());
  assert.equal(wallet.connection.value, 'connected');
  assert.equal(wallet.status.value, 'live');
  sockets[0].frame(envelope(2, null, 'syncing', 'Checking updates'));
  assert.equal(wallet.message.value, 'Checking updates');
  await wallet.refresh();
  assert.deepEqual(sockets[0].sent, ['refresh']);
  sockets[0].end();
  assert.equal(wallet.data.value.balance.total, 42);
  assert.equal(wallet.loading.value, false);
  assert.match(wallet.error.value, /Reconnecting/);
  assert.equal(wallet.connection.value, 'reconnecting');
  app.unmount();
  mounted = false;
  assert.equal(timers.jobs.size, 0);
  await wallet.refresh();
  assert.equal(sockets.length, 1);
  assert.equal(fetch.mock.callCount(), 0);
});

test('disposing before mount/start never creates a socket or a timer', () => {
  const { stream, sockets, timers } = setup();
  stream.dispose();
  stream.start();
  stream.refresh();
  assert.equal(sockets.length, 0);
  assert.equal(timers.jobs.size, 0);
  assert.equal(stream.getState().loading, false);
  assert.equal(stream.getState().connection, 'disconnected');
});

test('discovery coverage is preserved and malformed coverage is rejected', () => {
  const state = snapshot();
  state.discovery = { complete: false, receiveScanned: 200, changeScanned: 20, addressLimit: 200, gapLimit: 20 };
  assert.deepEqual(parseWalletEnvelope(JSON.stringify(envelope(1, state))).snapshot.discovery, state.discovery);
  for (const invalid of [{ complete: 'true' }, { receiveScanned: 201 }, { addressLimit: 0 }, { gapLimit: -1 }]) {
    assert.equal(parseWalletEnvelope(JSON.stringify(envelope(2, { ...state, discovery: { ...state.discovery, ...invalid } }))), null);
  }
});

test('freshness stays attached to the last snapshot through disconnect and restart', () => {
  const { stream, sockets } = setup();
  stream.start();
  sockets[0].open();
  sockets[0].frame({ ...envelope(1), updatedAt: 1000 });
  assert.equal(stream.getState().updatedAt, 1000);
  sockets[0].frame(envelope(2, null, 'syncing'));
  assert.equal(stream.getState().updatedAt, 1000);
  sockets[0].end();
  assert.equal(stream.getState().updatedAt, 1000);
  stream.refresh();
  sockets[1].open();
  sockets[1].frame(envelope(0, null, 'loading'));
  assert.equal(stream.getState().updatedAt, 1000);
  sockets[1].frame({ ...envelope(1), updatedAt: 2000 });
  assert.equal(stream.getState().updatedAt, 2000);
  assert.equal(parseWalletEnvelope(JSON.stringify({ ...envelope(2), updatedAt: 'invalid' })), null);
  stream.dispose();
});