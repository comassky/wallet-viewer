import { onMounted, onScopeDispose, ref, shallowRef, type Ref } from 'vue';
import type { RequestOptions } from '../services/http.ts';

export interface PolledResource<T> {
  data: Ref<T>;
  loading: Ref<boolean>;
  error: Ref<boolean>;
  refresh: () => Promise<void>;
}

/** Fetch on mount and on an interval, cancel on scope disposal; a failed poll flips `error` without clearing data. */
export function usePolledResource<T>(
  fetcher: (options: RequestOptions) => Promise<T>,
  initial: T,
  intervalMs = 60_000,
): PolledResource<T> {
  const data = shallowRef<T>(initial);
  const loading = ref(false);
  const error = ref(false);
  const controller = new AbortController();
  let timer: ReturnType<typeof setInterval> | undefined;

  async function refresh(): Promise<void> {
    if (controller.signal.aborted || loading.value) return;
    loading.value = true;
    try {
      const result = await fetcher({ signal: controller.signal });
      if (controller.signal.aborted) return;
      data.value = result;
      error.value = false;
    } catch {
      if (!controller.signal.aborted) error.value = true;
    } finally {
      loading.value = false;
    }
  }

  onMounted(() => {
    void refresh();
    timer = setInterval(() => void refresh(), intervalMs);
  });
  onScopeDispose(() => {
    controller.abort();
    clearInterval(timer);
  });

  return { data, loading, error, refresh };
}
