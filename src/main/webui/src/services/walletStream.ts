import type { WalletEnvelope, WalletSnapshot, WalletStatus } from '../types/wallet';

export type WalletConnection = 'connecting' | 'connected' | 'reconnecting' | 'disconnected';

export interface WalletStreamState {
  data: WalletSnapshot | null;
  loading: boolean;
  error: string | null;
  connection: WalletConnection;
  status: WalletStatus;
  message: string | null;
}

export type WalletSocket = Pick<WebSocket, 'readyState' | 'onopen' | 'onmessage' | 'onerror' | 'onclose' | 'send' | 'close'>;

export interface WalletStreamOptions {
  onState?: (state: WalletStreamState) => void;
  /** Defaults are resolved only on start, so importing/creating this service needs no browser. */
  url?: string;
  createSocket?: (url: string) => WalletSocket;
  scheduler?: {
    setTimeout: (callback: () => void, delay: number) => unknown;
    clearTimeout: (handle: unknown) => void;
  };
  random?: () => number;
  openTimeoutMs?: number;
  firstFrameTimeoutMs?: number;
  reconnectBaseMs?: number;
  reconnectMaxMs?: number;
}

export function walletStreamUrl(location: Pick<Location, 'protocol' | 'host'>): string {
  return `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/api/wallet/live`;
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function integer(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value);
}

function validSnapshot(value: unknown): value is WalletSnapshot {
  if (!record(value) || !record(value.balance) || !record(value.receiveAddress)) return false;
  const { balance, receiveAddress, transactions, utxos } = value;
  return integer(balance.confirmed) && integer(balance.unconfirmed) && integer(balance.total)
    && integer(receiveAddress.index) && receiveAddress.index >= 0
    && typeof receiveAddress.address === 'string' && typeof receiveAddress.path === 'string'
    && Array.isArray(transactions) && transactions.every(tx => record(tx)
      && typeof tx.txid === 'string' && integer(tx.amount) && integer(tx.received) && integer(tx.sent)
      && integer(tx.height) && integer(tx.confirmations)
      && (tx.timestamp === null || integer(tx.timestamp))
      && (tx.type === 'received' || tx.type === 'sent' || tx.type === 'self'))
    && Array.isArray(utxos) && utxos.every(utxo => record(utxo)
      && typeof utxo.txid === 'string' && typeof utxo.address === 'string'
      && integer(utxo.vout) && integer(utxo.value) && integer(utxo.height) && integer(utxo.confirmations));
}

/** Malformed or non-text frames are ignored without changing the accepted version. */
export function parseWalletEnvelope(frame: unknown): WalletEnvelope | null {
  if (typeof frame !== 'string') return null;
  try {
    const value: unknown = JSON.parse(frame);
    if (!record(value) || !integer(value.version) || value.version < 0
      || typeof value.status !== 'string' || !['loading', 'syncing', 'live', 'offline', 'error'].includes(value.status)
      || (value.message !== null && typeof value.message !== 'string')
      || (value.snapshot !== null && !validSnapshot(value.snapshot))) return null;
    return value as unknown as WalletEnvelope;
  } catch {
    return null;
  }
}

/**
 * One authoritative cache stream; no REST fetch, polling or rescan.
 * The browser answers protocol ping frames automatically. Only opening and the
 * first cache envelope have deadlines: a healthy unchanged wallet can be silent.
 */
export function createWalletStream(options: WalletStreamOptions = {}) {
  const scheduler = options.scheduler ?? {
    setTimeout: (callback: () => void, delay: number) => globalThis.setTimeout(callback, delay),
    clearTimeout: (handle: unknown) => globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>),
  };
  const random = options.random ?? Math.random;
  const createSocket = options.createSocket ?? (url => new WebSocket(url));
  let state: WalletStreamState = {
    data: null, loading: true, error: null, connection: 'connecting', status: 'loading', message: null,
  };
  let socket: WalletSocket | null = null;
  let deadline: unknown = null;
  let retryTimer: unknown = null;
  let generation = 0;
  let lastVersion = -1;
  let attempt = 0;
  let started = false;
  let disposed = false;

  function publish(update: Partial<WalletStreamState>): void {
    state = { ...state, ...update };
    options.onState?.(state);
  }

  function clearDeadline(): void {
    if (deadline !== null) scheduler.clearTimeout(deadline);
    deadline = null;
  }

  function clearRetry(): void {
    if (retryTimer !== null) scheduler.clearTimeout(retryTimer);
    retryTimer = null;
  }

  function releaseSocket(): void {
    // Invalidate queued events before close(), even when close dispatches synchronously.
    generation++;
    clearDeadline();
    const old = socket;
    socket = null;
    if (!old) return;
    old.onopen = old.onmessage = old.onerror = old.onclose = null;
    try { old.close(); } catch { /* A failed/connecting socket may already be unusable. */ }
  }

  function current(id: number): boolean {
    return !disposed && id === generation;
  }

  function fail(id: number, message: string): void {
    if (!current(id)) return;
    releaseSocket();
    publish({ connection: 'reconnecting', status: 'offline', message, error: message, loading: false });
    if (disposed) return;
    // Equal jitter in [cap/2, cap], with a hard cap even after long outages.
    const cap = Math.min(options.reconnectMaxMs ?? 30_000,
      (options.reconnectBaseMs ?? 500) * 2 ** Math.min(attempt++, 30));
    retryTimer = scheduler.setTimeout(() => {
      retryTimer = null;
      if (!disposed) connect();
    }, Math.round(cap * (0.5 + random() * 0.5)));
  }

  function connect(): void {
    if (disposed) return;
    clearRetry();
    releaseSocket();
    const id = generation;
    lastVersion = -1; // A restarted backend can begin again at version zero.
    publish({ connection: state.error || state.data ? 'reconnecting' : 'connecting' });
    if (disposed) return;
    try {
      const next = createSocket(options.url ?? walletStreamUrl(window.location));
      socket = next;
      deadline = scheduler.setTimeout(() => {
        fail(id, 'The wallet stream did not open in time. Reconnecting automatically.');
      }, options.openTimeoutMs ?? 10_000);
      next.onopen = () => {
        if (!current(id)) return;
        clearDeadline();
        publish({ connection: 'connected' });
        if (!current(id)) return;
        deadline = scheduler.setTimeout(() => {
          fail(id, 'No wallet cache state was received. Reconnecting automatically.');
        }, options.firstFrameTimeoutMs ?? 10_000);
      };
      next.onmessage = event => {
        if (!current(id)) return;
        const envelope = parseWalletEnvelope(event.data);
        if (!envelope || envelope.version <= lastVersion) return;
        lastVersion = envelope.version;
        clearDeadline();
        attempt = 0; // Opening alone is not evidence of a functioning cache stream.
        const data = envelope.snapshot ?? state.data;
        const error = envelope.status === 'offline' || envelope.status === 'error'
          ? envelope.message || (envelope.status === 'offline' ? 'Wallet backend is offline.' : 'Wallet synchronization failed.')
          : null;
        publish({
          data, status: envelope.status, message: envelope.message, error, connection: 'connected',
          loading: !data && (envelope.status === 'loading' || envelope.status === 'syncing'),
        });
      };
      next.onerror = () => fail(id, 'Wallet connection failed. Reconnecting automatically.');
      next.onclose = () => fail(id, 'Wallet connection lost. Reconnecting automatically.');
    } catch {
      fail(id, 'Unable to connect to the wallet stream. Reconnecting automatically.');
    }
  }

  function start(): void {
    if (started || disposed) return;
    started = true;
    connect();
  }

  function refresh(): void {
    if (disposed) return;
    if (!started) { start(); return; }
    if (socket?.readyState === 1) {
      try { socket.send('refresh'); }
      catch { fail(generation, 'Unable to replay the wallet cache. Reconnecting automatically.'); }
    } else if (socket?.readyState !== 0) {
      connect(); // Manual retry bypasses backoff, but never opens concurrent sockets.
    }
  }

  function dispose(): void {
    if (disposed) return;
    disposed = true;
    clearRetry();
    releaseSocket();
    publish({ connection: 'disconnected', loading: false });
  }

  return { start, refresh, dispose, getState: (): WalletStreamState => state };
}