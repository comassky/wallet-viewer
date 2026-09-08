<script setup lang="ts">
defineProps<{ confirmations: number; compact?: boolean }>();
</script>

<template>
  <span class="inline-flex items-center gap-1.5" :class="[compact ? 'text-xs' : '', confirmations > 0 ? (compact ? 'text-slate-400' : 'text-slate-300') : 'text-accent']">
    <!-- Keep spinning until the transaction reaches 5 confirmations. -->
    <span v-if="confirmations < 5" class="h-3 w-3 shrink-0 animate-spin rounded-full border-2 border-slate-600 border-t-accent motion-reduce:animate-none" role="status" aria-label="Awaiting confirmations" />
    <template v-if="confirmations > 0">{{ confirmations }}</template>
  </span>
</template>