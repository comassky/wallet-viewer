<script setup lang="ts">
defineProps<{ address: string }>();
</script>

<template>
  <span class="break-all font-mono leading-relaxed tracking-normal text-slate-400"><span class="font-semibold text-accent">{{ address.slice(0, 8) }}</span>{{ address.slice(8, -8) }}<span class="font-semibold text-accent">{{ address.slice(Math.max(8, address.length - 8)) }}</span></span>
</template>