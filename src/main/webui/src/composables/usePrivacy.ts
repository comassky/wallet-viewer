import { readonly, ref, watch } from 'vue';
import { readStorage, writeStorage } from '../utils/storage.ts';

const storageKey = 'wallet-viewer.privacy';

const hidden = ref(readStorage(storageKey) === '1');

watch(hidden, value => {
  writeStorage(storageKey, value ? '1' : '0');
}, { flush: 'sync' });

export function usePrivacy() {
  return {
    hidden: readonly(hidden),
    conceal: (value: string): string => hidden.value ? 'Hidden' : value,
    toggle: (): void => { hidden.value = !hidden.value; },
  };
}
