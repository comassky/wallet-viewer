import axios from 'axios';

export interface RequestOptions {
  signal?: AbortSignal;
  timeoutMs?: number;
}

const REQUEST_TIMEOUT_MS = 120_000;

/** One REST client; wallet WebSocket transport remains independent. No automatic retries. */
export const httpClient = axios.create({
  headers: { Accept: 'application/json' },
  timeout: REQUEST_TIMEOUT_MS,
  responseType: 'json',
  transitional: { silentJSONParsing: false, clarifyTimeoutError: true },
});

/** Shared JSON transport: bounded requests, caller cancellation and readable failures. */
export async function requestJson<T>(url: string, { signal, timeoutMs = REQUEST_TIMEOUT_MS }: RequestOptions = {}): Promise<T> {
  // Axios treats zero as unlimited: never silently remove the request deadline.
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new RangeError('Request timeout must be positive and finite.');
  try {
    const response = await httpClient.get<T>(url, { signal, timeout: timeoutMs });
    return response.data;
  } catch (error) {
    if (axios.isCancel(error)) {
      // Keep the previous AbortSignal contract for composables and their callers.
      throw signal?.reason ?? new DOMException('The request was aborted.', 'AbortError');
    }
    if (axios.isAxiosError(error)) {
      if (error.code === 'ETIMEDOUT' || error.code === 'ECONNABORTED') {
        throw new Error('The server took too long to respond. Please try again.');
      }
      // A JSON parse error can also carry the successful HTTP response.
      if (error.response && (error.response.status < 200 || error.response.status >= 300)) {
        const data: unknown = error.response.data;
        const body = typeof data === 'string' ? data : data == null ? '' : JSON.stringify(data);
        throw new Error(`HTTP ${error.response.status}${body ? ` — ${body}` : ''}`);
      }
      if (error.code === 'ERR_BAD_RESPONSE' || error.name === 'SyntaxError') {
        throw new Error('The server returned invalid JSON. Please try again.');
      }
      throw new Error(`Network unreachable: ${error.message}`);
    }
    throw error;
  }
}