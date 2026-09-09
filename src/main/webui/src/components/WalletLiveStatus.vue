<script setup lang="ts">
import { computed } from 'vue';
import type { WalletConnection } from '../services/walletStream';
import type { WalletStatus } from '../types/wallet';

const props = defineProps<{
  connection: WalletConnection;
  status: WalletStatus;
  message: string | null;
  hasData: boolean;
}>();
defineEmits<{ retry: [] }>();

const live = computed(() => props.connection === 'connected' && props.status === 'live');
const retryable = computed(() => props.connection === 'reconnecting' || props.connection === 'disconnected'
  || props.status === 'offline' || props.status === 'error');
const label = computed(() => {
  if (props.connection === 'connecting') return 'Connecting';
  if (props.connection === 'reconnecting') return 'Reconnecting';
  if (props.connection === 'disconnected') return 'Disconnected';
  return { loading: 'Loading wallet', syncing: 'Syncing', live: 'Live', offline: 'Backend offline', error: 'Sync error' }[props.status];
});
</script>

<template>
  <section class="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-800 bg-slate-900 px-4 py-3">
    <div role="status" aria-live="polite" aria-atomic="true" class="min-w-0 text-sm">
      <span class="inline-flex items-center gap-2 font-medium" :class="live ? 'text-emerald-400' : 'text-amber-300'">
        <span aria-hidden="true" class="h-2 w-2 rounded-full" :class="live ? 'bg-emerald-400' : 'bg-amber-300'" />
        {{ label }}
      </span>
      <span v-if="hasData && !live" class="ml-2 text-slate-400">Showing the last received snapshot; it may be out of date.</span>
      <p v-if="message" class="mt-1 break-words text-slate-400">{{ message }}</p>
    </div>
    <button
      v-if="hasData && retryable"
      type="button"
      class="shrink-0 rounded-lg border border-slate-700 px-3 py-1.5 text-sm hover:border-accent"
      @click="$emit('retry')"
    >Retry</button>
  </section>
</template>