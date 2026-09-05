<script setup lang="ts">
import { Activity, Check, Coins, Copy, Server, ShieldCheck, X } from '@lucide/vue';

defineProps<{ name: 'shield' | 'server' | 'activity' | 'coins' | 'copy' | 'check' | 'close' }>();
// Explicit imports keep only the icons used by the interface in the production bundle.
const icons = {
  shield: ShieldCheck, server: Server,
  activity: Activity, coins: Coins, copy: Copy, check: Check, close: X,
};
</script>

<template>
  <component :is="icons[name]" :stroke-width="1.7" aria-hidden="true" focusable="false" class="h-4 w-4 shrink-0" />
</template>