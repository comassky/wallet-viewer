<script setup lang="ts">
defineProps<{ text?: string }>();
</script>

<template>
  <span class="group relative inline-flex">
    <slot />
    <span
      v-if="text"
      role="tooltip"
      class="pointer-events-none absolute left-1/2 top-full z-20 mt-2 w-max max-w-xs -translate-x-1/2 scale-95 rounded-lg border border-slate-700 bg-slate-900/95 px-3 py-2 text-xs font-normal leading-relaxed text-slate-200 opacity-0 shadow-xl backdrop-blur transition duration-150 group-hover:scale-100 group-hover:opacity-100 group-focus-within:scale-100 group-focus-within:opacity-100 motion-reduce:transition-none"
    >
      <span class="absolute -top-1 left-1/2 h-2 w-2 -translate-x-1/2 rotate-45 border-l border-t border-slate-700 bg-slate-900/95" aria-hidden="true" />
      {{ text }}
    </span>
  </span>
</template>
