import { onScopeDispose, ref } from 'vue';
import { showToast } from '@/composables/useToast.ts';

/** Independent copy feedback per component; stale asynchronous results are ignored. */
export function useClipboard() {
  const copied = ref(false);
  const error = ref<string | null>(null);
  let timer: ReturnType<typeof setTimeout> | undefined;
  let version = 0;
  let disposed = false;

  function reset(): void {
    clearTimeout(timer);
    version++;
    copied.value = false;
    error.value = null;
  }

  async function copy(text: string, label = 'value'): Promise<void> {
    if (disposed) return;
    reset();
    const current = version;
    try {
      await navigator.clipboard.writeText(text);
      if (disposed || current !== version) return;
      copied.value = true;
      showToast(`${label.charAt(0).toUpperCase()}${label.slice(1)} copied`);
      timer = setTimeout(() => (copied.value = false), 1500);
    } catch {
      if (disposed || current !== version) return;
      error.value = 'Copy unavailable. Select and copy the address manually.';
    }
  }

  onScopeDispose(() => {
    disposed = true;
    reset();
  });
  return { copied, error, copy, reset };
}