<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, useTemplateRef, watch } from 'vue';
import type { WalletConnection } from '../services/walletStream';
import type { WalletStatus } from '../types/wallet';
import { useElectrumServer } from '../composables/useElectrumServer';
import UiIcon from './UiIcon.vue';

const props = defineProps<{
  connection: WalletConnection;
  status: WalletStatus;
  message: string | null;
}>();
defineEmits<{ retry: [] }>();

const live = computed(() => props.connection === 'connected' && props.status === 'live');
const root = useTemplateRef<HTMLElement>('root');
const trigger = useTemplateRef<HTMLButtonElement>('trigger');
const opened = ref(false);
const panelLeft = ref(0);
const { server, loading, error, load, reset } = useElectrumServer();
const retryable = computed(() => props.connection === 'reconnecting' || props.connection === 'disconnected'
  || props.status === 'offline' || props.status === 'error');
const label = computed(() => {
  if (props.connection === 'connecting') return 'Connecting';
  if (props.connection === 'reconnecting') return 'Reconnecting';
  if (props.connection === 'disconnected') return 'Disconnected';
  return { loading: 'Loading wallet', syncing: 'Syncing', live: 'Live', offline: 'Electrum offline', error: 'Sync error' }[props.status];
});

function show() {
  if (opened.value) return;
  const left = root.value?.getBoundingClientRect().left ?? 16;
  const width = Math.min(336, window.innerWidth - 32);
  panelLeft.value = Math.max(16 - left, Math.min(0, window.innerWidth - 16 - left - width));
  opened.value = true;
  if (props.connection === 'connected') void load();
}
function leave() {
  if (!root.value?.contains(document.activeElement)) opened.value = false;
}
function blur(event: FocusEvent) {
  // Focus lost to nothing (e.g. Refresh disabling itself while loading) must keep the panel open.
  if (!(event.relatedTarget instanceof Node)) return;
  if (!root.value?.contains(event.relatedTarget)) opened.value = false;
}
function outside(event: PointerEvent) {
  if (event.target instanceof Node && !root.value?.contains(event.target)) opened.value = false;
}
function dismiss() { opened.value = false; trigger.value?.focus(); opened.value = false; }
watch([() => props.connection, () => props.status], () => {
  reset();
  if (opened.value && props.connection === 'connected') void load();
});
onMounted(() => document.addEventListener('pointerdown', outside));
onScopeDispose(() => document.removeEventListener('pointerdown', outside));
</script>

<template>
  <div ref="root" class="relative" @mouseenter="show" @mouseleave="leave" @focusout="blur" @keydown.esc.stop.prevent="dismiss">
    <button
      ref="trigger" type="button" aria-controls="electrum-server-info" :aria-expanded="opened"
      :aria-label="`${label}. Electrum server information`"
      class="inline-flex items-center gap-2 rounded-full border px-3 text-xs font-medium transition"
      :class="live ? 'border-accent/25 bg-accent/10 text-accent hover:bg-accent/15' : 'border-amber-400/30 bg-amber-400/10 text-amber-300'"
      @focus="show" @click="show"
    >
      <span class="h-1.5 w-1.5 rounded-full" :class="live ? 'bg-accent live-dot' : 'bg-amber-300 motion-safe:animate-pulse'" aria-hidden="true" />
      <span role="status" aria-live="polite" aria-atomic="true">{{ label }}</span>
    </button>
    <div v-if="opened" id="electrum-server-info" role="region" aria-labelledby="electrum-server-title" :style="{ left: `${panelLeft}px` }" class="absolute top-full z-50 w-[min(21rem,calc(100vw-2rem))] pt-2">
      <div class="rounded-2xl border border-accent/25 bg-slate-900 p-4 shadow-2xl">
        <h2 id="electrum-server-title" class="mb-3 flex items-center gap-2 text-sm font-semibold"><UiIcon name="server" class="text-accent" />Electrum connection</h2>
        <p v-if="connection !== 'connected'" class="text-xs text-amber-300">Browser disconnected from backend. Server state cannot be verified.</p>
        <p v-else-if="loading" role="status" class="text-xs text-slate-400">Reading server information…</p>
        <p v-else-if="error" role="status" class="text-xs text-amber-300">{{ error }}</p>
        <dl v-else-if="server" class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 text-xs">
          <dt class="text-slate-500">Server</dt><dd class="break-all text-right font-mono">{{ server.host }}:{{ server.port }}</dd>
          <dt class="text-slate-500">Connection</dt><dd class="text-right" :class="server.connected ? 'text-accent' : 'text-amber-300'">{{ server.connected ? 'Connected' : 'Disconnected' }}</dd>
          <dt class="text-slate-500">Software</dt><dd class="break-all text-right">{{ server.serverVersion || 'Not available' }}</dd>
          <dt class="text-slate-500">Protocol</dt><dd class="break-all text-right font-mono">{{ server.protocolVersion || 'Not available' }}</dd>
          <dt class="text-slate-500">Transport</dt><dd class="text-right">{{ server.tls ? 'TLS · certificate verified' : 'TCP · unencrypted' }}</dd>
        </dl>
        <p v-if="message" class="mt-3 break-words border-t border-slate-800 pt-3 text-xs text-slate-400">{{ message }}</p>
        <p class="mt-3 text-[11px] text-slate-500">Connection information is read from the backend cache when opened. Software and protocol are reported by the server.</p>
        <div class="mt-3 flex justify-between gap-2">
          <button v-if="retryable" type="button" class="button-secondary rounded-lg px-3 text-xs" @click="$emit('retry')">Reconnect / replay cache</button>
          <button v-else type="button" :disabled="loading" class="button-secondary rounded-lg px-3 text-xs disabled:opacity-40" @click="load">Refresh info</button>
          <button type="button" class="rounded-lg px-3 text-xs text-slate-400 hover:text-accent" @click="dismiss">Close</button>
        </div>
      </div>
    </div>
  </div>
</template>