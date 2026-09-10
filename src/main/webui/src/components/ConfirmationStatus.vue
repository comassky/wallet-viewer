<script setup lang="ts">
defineProps<{ confirmations: number; compact?: boolean }>();
</script>

<template>
  <span v-if="confirmations === 0" class="inline-flex items-center gap-1.5 rounded-md border border-amber-400/20 bg-amber-400/10 px-2 py-0.5 text-xs font-medium text-amber-300">
    <span class="h-1.5 w-1.5 shrink-0 rounded-full bg-amber-300" aria-hidden="true" />
    Pending
  </span>
  <span v-else class="inline-flex items-center gap-1.5 tabular-nums" :class="compact ? 'text-xs text-slate-400' : 'text-slate-300'">
    <span v-if="confirmations >= 1 && confirmations < 5" class="h-3 w-3 shrink-0 animate-spin rounded-full border-2 border-slate-600 border-t-accent motion-reduce:animate-none" role="status" aria-label="Awaiting confirmations" />
    {{ confirmations }}
  </span>
</template>