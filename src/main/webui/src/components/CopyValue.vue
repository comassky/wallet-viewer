<script setup lang="ts">
import { watch } from 'vue';
import { useClipboard } from '@/composables/useClipboard';
import AppTooltip from '@/components/AppTooltip.vue';
import UiIcon from '@/components/UiIcon.vue';

const props = withDefaults(defineProps<{ value: string; display?: string; label?: string; statusRight?: boolean }>(), { label: 'value' });
const { copied, error, copy, reset } = useClipboard();
watch(() => props.value, reset);
</script>

<template>
  <span class="min-w-0 max-w-full align-middle" :class="statusRight ? 'flex items-center gap-2' : 'inline-block'">
    <AppTooltip :text="copied ? 'Copied' : `Copy ${label}`" class="min-w-0 max-w-full" :class="statusRight ? 'flex-1' : ''">
    <button
      type="button"
      class="group/copy max-w-full select-text break-all rounded-sm px-1 py-1 text-left font-mono text-slate-300 transition-colors hover:text-accent focus-visible:text-accent"
      :class="statusRight ? 'min-w-0 w-full' : ''"
      :aria-label="`Copy ${label}: ${value}`"
      @click.stop="copy(value, label)"
    ><slot>{{ display ?? value }}</slot> <UiIcon :name="copied ? 'check' : 'copy'" class="ml-1 inline-block h-3 w-3 align-middle transition-colors" :class="copied ? 'text-emerald-400' : 'text-slate-500 group-hover/copy:text-accent group-focus-visible/copy:text-accent'" /></button>
    </AppTooltip>
    <span v-if="error" role="status" class="break-words font-sans text-xs text-rose-400" :class="statusRight ? 'shrink-0' : 'block'">{{ error }}</span>
  </span>
</template>