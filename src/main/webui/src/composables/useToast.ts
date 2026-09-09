import { readonly, ref } from 'vue';

export interface Toast {
  id: number;
  message: string;
}

const toasts = ref<Toast[]>([]);
let nextId = 0;

/** Global, ephemeral copy/action feedback shown bottom-right; auto-dismisses. */
export function showToast(message: string, durationMs = 1000): void {
  const id = nextId++;
  toasts.value = [...toasts.value, { id, message }];
  setTimeout(() => {
    toasts.value = toasts.value.filter(toast => toast.id !== id);
  }, durationMs);
}

export function useToasts() {
  return { toasts: readonly(toasts) };
}
