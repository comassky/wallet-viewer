<script setup lang="ts">
import { watch } from 'vue';
import { useClipboard } from '../composables/useClipboard';
import UiIcon from './UiIcon.vue';

const props = withDefaults(defineProps<{ value: string; display?: string; label?: string; statusRight?: boolean }>(), { label: 'value' });
const { copied, error, copy, reset } = useClipboard();
watch(() => props.value, reset);
</script>

<template>
  <span class="min-w-0 max-w-full align-middle" :class="statusRight ? 'flex items-center gap-2' : 'inline-block'">
    <button
      type="button"
      class="group max-w-full select-text break-all rounded-sm px-1 py-1 text-left font-mono text-slate-300 transition hover:bg-accent/10 hover:text-accent"
      :class="statusRight ? 'min-w-0 flex-1' : ''"
      :title="`Copy ${label}: ${value}`"
      :aria-label="`Copy ${label}: ${value}`"
      @click.stop="copy(value)"
    >{{ display ?? value }} <UiIcon :name="copied ? 'check' : 'copy'" class="ml-1 inline-block h-3 w-3 align-middle text-accent opacity-40 group-hover:opacity-100" /></button>
    <span role="status" class="break-words font-sans text-xs" :class="[error ? 'text-rose-400' : 'text-emerald-400', statusRight ? 'shrink-0' : 'block']">{{ error || (copied ? '✓ Copied' : '') }}</span>
  </span>
</template>