<script setup lang="ts">
import { watch } from 'vue';
import { useClipboard } from '../composables/useClipboard';

const props = defineProps<{ address: string; compact?: boolean }>();
const { copied, error, copy, reset } = useClipboard();

watch(() => props.address, reset);
defineExpose({ reset });
</script>

<template>
  <button
    type="button"
    @click="copy(address)"
    class="button-secondary"
    :class="compact ? 'mt-2 rounded-md px-2.5 py-1 text-xs' : 'mt-4 rounded-lg px-3 text-sm'"
  >{{ copied ? '✓ Copied' : 'Copy address' }}</button>
  <p role="status" class="mt-2 text-xs" :class="error ? 'text-rose-400' : 'text-slate-400'">
    {{ error || (copied ? 'Address copied.' : '') }}
  </p>
</template>