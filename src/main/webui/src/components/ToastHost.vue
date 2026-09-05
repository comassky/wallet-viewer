<script setup lang="ts">
import { ref, watch } from 'vue';
import { useToasts } from '../composables/useToast';
import UiIcon from './UiIcon.vue';

const { toasts } = useToasts();
const host = ref<HTMLElement | null>(null);

// Enter the top layer only while toasts are visible, so they render above an already-open
// modal <dialog> (and its blurred backdrop). Re-showing places it last in the top-layer stack.
watch(() => toasts.value.length, count => {
  const element = host.value;
  if (!element || typeof element.showPopover !== 'function') return;
  try {
    if (count > 0 && !element.matches(':popover-open')) element.showPopover();
    else if (count === 0 && element.matches(':popover-open')) element.hidePopover();
  } catch { /* Popover API unavailable or state already changed: normal stacking still applies. */ }
});
</script>

<template>
  <div ref="host" popover="manual" class="toast-host pointer-events-none fixed bottom-4 right-4 z-50 flex flex-col items-end gap-2" aria-live="polite" aria-atomic="true">
    <TransitionGroup name="toast">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        role="status"
        class="pointer-events-auto flex items-center gap-2 rounded-lg border border-slate-700 bg-slate-900/95 px-4 py-2.5 text-sm text-slate-100 shadow-lg backdrop-blur"
      >
        <UiIcon name="check" class="h-4 w-4 text-accent" />{{ toast.message }}
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
/* Neutralize the User-Agent popover box so only our bottom-right layout remains. */
.toast-host[popover] {
  top: auto;
  left: auto;
  margin: 0;
  border: 0;
  padding: 0;
  width: auto;
  height: auto;
  max-width: none;
  max-height: none;
  overflow: visible;
  background: transparent;
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(0.5rem);
}

@media (prefers-reduced-motion: reduce) {
  .toast-enter-active,
  .toast-leave-active {
    transition: none;
  }
}
</style>
