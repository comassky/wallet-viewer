<script setup lang="ts">
import { useToasts } from '../composables/useToast';
import UiIcon from './UiIcon.vue';

const { toasts } = useToasts();
</script>

<template>
  <div class="pointer-events-none fixed bottom-4 right-4 z-50 flex flex-col items-end gap-2" aria-live="polite" aria-atomic="true">
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
