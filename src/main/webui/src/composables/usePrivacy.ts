import { ref, watch } from 'vue';

const storageKey = 'wallet-viewer.privacy';

function read(): boolean {
  try { return localStorage.getItem(storageKey) === '1'; } catch { return false; }
}

// Shared singleton so every component reflects the same blur state.
const hidden = ref(read());

watch(hidden, value => {
  try { localStorage.setItem(storageKey, value ? '1' : '0'); } catch { /* Storage is optional. */ }
  document.documentElement.classList.toggle('privacy-blur', value);
}, { immediate: true });

/** Toggle that blurs sensitive amounts app-wide via a root class. */
export function usePrivacy() {
  return { hidden, toggle: (): void => { hidden.value = !hidden.value; } };
}
