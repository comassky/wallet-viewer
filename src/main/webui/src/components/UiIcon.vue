<script setup lang="ts">
import { Activity, ArrowRight, Check, ChevronLeft, ChevronRight, CircleCheck, Coins, Copy, Inbox, List, QrCode, SearchCheck, Server, ShieldCheck, TriangleAlert, Waypoints, X } from '@lucide/vue';

defineProps<{ name: 'shield' | 'server' | 'activity' | 'coins' | 'copy' | 'check' | 'close' | 'chevron-left' | 'chevron-right' | 'qr-code' | 'search' | 'circle-check' | 'alert' | 'graph' | 'list' | 'arrow-right' | 'inbox' }>();
// Explicit imports keep only the icons used by the interface in the production bundle.
const icons = {
  shield: ShieldCheck, server: Server,
  activity: Activity, coins: Coins, copy: Copy, check: Check, close: X,
  'chevron-left': ChevronLeft, 'chevron-right': ChevronRight,
  'qr-code': QrCode, search: SearchCheck, 'circle-check': CircleCheck, alert: TriangleAlert,
  graph: Waypoints, list: List, 'arrow-right': ArrowRight, inbox: Inbox,
};
</script>

<template>
  <component :is="icons[name]" :stroke-width="1.7" aria-hidden="true" focusable="false" class="h-4 w-4 shrink-0" />
</template>