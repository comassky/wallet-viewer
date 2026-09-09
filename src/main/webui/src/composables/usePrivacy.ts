import { ref, watch } from 'vue';
import { readStorage, writeStorage } from '../utils/storage.ts';

const storageKey = 'wallet-viewer.privacy';

// Shared singleton so every component reflects the same blur state.
const hidden = ref(readStorage(storageKey) === '1');

watch(hidden, value => {
  writeStorage(storageKey, value ? '1' : '0');
  document.documentElement.classList.toggle('privacy-blur', value);
}, { immediate: true });

/** Toggle that blurs sensitive amounts app-wide via a root class. */
export function usePrivacy() {
  return { hidden, toggle: (): void => { hidden.value = !hidden.value; } };
}
