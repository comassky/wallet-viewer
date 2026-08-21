<script setup lang="ts">
import { watch } from 'vue';
import { useClipboard } from '../composables/useClipboard';

const props = withDefaults(defineProps<{ value: string; display?: string; label?: string }>(), { label: 'value' });
const { copied, error, copy, reset } = useClipboard();
watch(() => props.value, reset);
</script>

<template>
  <span class="inline-block min-w-0 max-w-full align-middle">
    <button
      type="button"
      class="max-w-full select-text break-all rounded px-1 py-1 text-left font-mono text-sky-400 transition hover:bg-sky-400/10 hover:text-sky-300"
      :title="`Copy ${label}: ${value}`"
      :aria-label="`Copy ${label}: ${value}`"
      @click.stop="copy(value)"
    >{{ display ?? value }}</button>
    <span role="status" class="block break-words font-sans text-xs" :class="error ? 'text-rose-400' : 'text-emerald-400'">{{ error || (copied ? '✓ Copied' : '') }}</span>
  </span>
</template>