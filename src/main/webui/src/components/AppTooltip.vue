<script setup lang="ts">
defineProps<{ text?: string | undefined; alignEnd?: boolean }>();
</script>

<template>
  <span class="group relative inline-flex">
    <slot />
    <span
      v-if="text"
      role="tooltip"
      class="pointer-events-none invisible fixed inset-x-4 bottom-4 z-20 scale-95 rounded-lg border border-slate-700 bg-slate-900/95 px-3 py-2 text-xs font-normal leading-relaxed text-slate-200 opacity-0 shadow-xl backdrop-blur transition duration-150 group-hover:visible group-hover:scale-100 group-hover:opacity-100 group-focus-within:visible group-focus-within:scale-100 group-focus-within:opacity-100 motion-reduce:transition-none lg:absolute lg:inset-x-auto lg:bottom-auto lg:top-full lg:mt-2 lg:w-max lg:max-w-xs"
      :class="alignEnd ? 'lg:right-0' : 'lg:left-1/2 lg:-translate-x-1/2'"
    >
      <span class="absolute -top-1 hidden h-2 w-2 rotate-45 border-l border-t border-slate-700 bg-slate-900/95 lg:block" :class="alignEnd ? 'right-5' : 'left-1/2 -translate-x-1/2'" aria-hidden="true" />
      {{ text }}
    </span>
  </span>
</template>
