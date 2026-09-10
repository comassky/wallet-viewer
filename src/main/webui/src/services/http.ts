export interface RequestOptions {
  signal?: AbortSignal;
  timeoutMs?: number;
}

const REQUEST_TIMEOUT_MS = 120_000;

/** Shared JSON transport: bounded requests, caller cancellation and readable failures. */
export async function requestJson(url: string, { signal, timeoutMs = REQUEST_TIMEOUT_MS }: RequestOptions = {}): Promise<unknown> {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new RangeError('Request timeout must be positive and finite.');
  signal?.throwIfAborted();
  const controller = new AbortController();
  const requestSignal = AbortSignal.any(signal ? [signal, controller.signal] : [controller.signal]);
  const timeout = setTimeout(() => controller.abort(new Error('The server took too long to respond. Please try again.')), timeoutMs);
  try {
    let response: Response;
    let body: string;
    try {
      response = await fetch(url, { headers: { Accept: 'application/json' }, signal: requestSignal });
      body = await response.text();
      requestSignal.throwIfAborted();
    } catch (error) {
      requestSignal.throwIfAborted();
      throw new Error(`Network unreachable: ${error instanceof Error ? error.message : String(error)}`);
    }
    if (!response.ok) throw new Error(`HTTP ${response.status}${body ? ` — ${body}` : ''}`);
    try {
      return JSON.parse(body) as unknown;
    } catch {
      throw new Error('The server returned invalid JSON. Please try again.');
    }
  } finally {
    clearTimeout(timeout);
  }
}