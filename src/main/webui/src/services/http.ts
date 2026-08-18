export interface RequestOptions {
  signal?: AbortSignal;
}

const REQUEST_TIMEOUT_MS = 120_000;

/** Shared JSON transport: bounded requests, caller cancellation and readable failures. */
export async function requestJson<T>(url: string, { signal }: RequestOptions = {}): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort(signal?.reason);
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) abort();
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, REQUEST_TIMEOUT_MS);
  try {
    let response: Response;
    try {
      response = await fetch(url, {
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      });
    } catch (error) {
      if (controller.signal.aborted) throw error;
      throw new Error(`Network unreachable: ${error instanceof Error ? error.message : String(error)}`);
    }
    if (!response.ok) {
      const body = await response.text();
      throw new Error(`HTTP ${response.status}${body ? ` — ${body}` : ''}`);
    }
    return await response.json() as T;
  } catch (error) {
    if (timedOut) throw new Error('The server took too long to respond. Please try again.');
    throw error;
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener('abort', abort);
  }
}