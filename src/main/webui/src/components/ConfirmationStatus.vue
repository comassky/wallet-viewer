<script setup lang="ts">
import { ref } from 'vue';
import AppTooltip from '@/components/AppTooltip.vue';
import UiIcon from '@/components/UiIcon.vue';

defineProps<{ confirmations: number; compact?: boolean; iconOnly?: boolean }>();
const dismissed = ref(false);
</script>

<template>
  <AppTooltip v-if="iconOnly && confirmations > 0" :text="dismissed ? undefined : `${confirmations} confirmation${confirmations === 1 ? '' : 's'}`" align-end>
    <button type="button" :aria-label="`${confirmations} confirmation${confirmations === 1 ? '' : 's'}`" class="inline-flex h-11 w-11 items-center justify-center rounded-md transition" :class="confirmations >= 5 ? 'text-emerald-400 hover:bg-emerald-400/10 focus-visible:bg-emerald-400/10' : 'text-accent hover:bg-accent/10 focus-visible:bg-accent/10'" @mouseenter="dismissed = false" @focus="dismissed = false" @click.stop="dismissed = false" @keydown.esc.stop="dismissed = true">
      <UiIcon v-if="confirmations >= 5" name="circle-check" />
      <span v-else class="h-3 w-3 shrink-0 animate-spin rounded-full border-2 border-slate-600 border-t-accent motion-reduce:animate-none" aria-hidden="true" />
    </button>
  </AppTooltip>
  <span v-else-if="confirmations === 0" class="inline-flex items-center gap-1.5 rounded-md border border-amber-400/20 bg-amber-400/10 px-2 py-0.5 text-xs font-medium text-amber-300">
    <span class="h-1.5 w-1.5 shrink-0 rounded-full bg-amber-300" aria-hidden="true" />
    Pending
  </span>
  <span v-else class="inline-flex items-center gap-1.5 tabular-nums" :class="compact ? 'text-xs text-slate-400' : 'text-slate-300'">
    <span v-if="confirmations >= 1 && confirmations < 5" class="h-3 w-3 shrink-0 animate-spin rounded-full border-2 border-slate-600 border-t-accent motion-reduce:animate-none" role="status" aria-label="Awaiting confirmations" />
    {{ confirmations }}
  </span>
</template>