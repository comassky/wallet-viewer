<script setup lang="ts">
import { watch } from 'vue';
import { useClipboard } from '@/composables/useClipboard';
import UiIcon from '@/components/UiIcon.vue';

const props = defineProps<{ value: string }>();
const { copied, error, copy, reset } = useClipboard();
watch(() => props.value, reset);
</script>

<template>
  <div>
    <button type="button" class="button-secondary inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm font-semibold" @click.stop="copy(value, 'address')">
      <UiIcon :name="copied ? 'check' : 'copy'" />{{ copied ? 'Address copied' : 'Copy address' }}
    </button>
    <p v-if="error" role="status" class="mt-2 text-xs text-rose-400">{{ error }}</p>
  </div>
</template>