<script setup lang="ts">
import { useTemplateRef, watch } from 'vue';
import { useToasts } from '@/composables/useToast';
import UiIcon from '@/components/UiIcon.vue';

const { toasts } = useToasts();
const props = defineProps<{ reconnecting?: boolean }>();
const host = useTemplateRef<HTMLElement>('host');

// Enter the top layer only while toasts are visible, so they render above an already-open
// modal <dialog> (and its blurred backdrop). Re-showing places it last in the top-layer stack.
watch([() => toasts.value.length + Number(!!props.reconnecting), host], ([count]) => {
  const element = host.value;
  if (!element || typeof element.showPopover !== 'function') return;
  try {
    if (count > 0 && !element.matches(':popover-open')) element.showPopover();
    else if (count === 0 && element.matches(':popover-open')) element.hidePopover();
  } catch { /* Popover API unavailable or state already changed: normal stacking still applies. */ }
}, { flush: 'post' });
</script>

<template>
  <div ref="host" popover="manual" class="toast-host pointer-events-none fixed bottom-6 right-6 z-50 flex flex-col items-end gap-3" aria-live="polite" aria-atomic="true">
    <TransitionGroup name="toast">
      <div
        v-if="reconnecting"
        key="reconnecting"
        role="status"
        class="pointer-events-auto flex max-w-full items-center gap-3 rounded-lg border border-accent/30 bg-slate-900/95 px-5 py-3.5 text-base font-medium text-slate-100 shadow-xl backdrop-blur"
      >
        <span class="h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-slate-600 border-t-accent motion-reduce:animate-none" aria-hidden="true" />
        <span class="min-w-0 break-words">Reconnecting to wallet...</span>
      </div>
      <div
        v-for="toast in toasts"
        :key="toast.id"
        role="status"
        class="pointer-events-auto flex items-center gap-3 rounded-xl border border-slate-700 bg-slate-900/95 px-5 py-3.5 text-base font-medium text-slate-100 shadow-xl backdrop-blur"
      >
        <UiIcon name="check" class="h-5 w-5 text-accent" />{{ toast.message }}
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
  max-width: calc(100vw - 3rem);
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
