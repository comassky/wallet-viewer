<script setup lang="ts">
defineProps<{ confirmations: number; compact?: boolean }>();
</script>

<template>
  <span :class="[compact ? 'text-xs' : '', confirmations > 0 ? (compact ? 'text-slate-400' : 'text-slate-300') : 'text-accent']">
    {{ confirmations > 0 ? confirmations : 'Pending' }}
  </span>
</template>