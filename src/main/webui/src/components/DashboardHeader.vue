<script setup lang="ts">
import { currencies, type Currency } from '../currency';

defineProps<{ currency: Currency; loading: boolean }>();
defineEmits<{ 'update:currency': [value: Currency]; refresh: [] }>();
</script>

<template>
  <header class="mb-6 flex flex-wrap items-center justify-between gap-4">
    <h1 class="flex items-center gap-2 text-xl font-semibold">
      <span class="text-2xl text-accent">₿</span> Wallet Viewer
    </h1>
    <div class="flex min-w-0 flex-wrap items-center gap-3">
      <div role="group" aria-label="Display currency" class="flex flex-wrap rounded-lg border border-slate-700 bg-slate-900 p-1">
        <button
          v-for="unit in currencies"
          :key="unit"
          type="button"
          :aria-pressed="currency === unit"
          @click="$emit('update:currency', unit)"
          class="rounded-md px-3 py-1.5 text-xs font-semibold transition focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent"
          :class="currency === unit ? 'bg-accent text-slate-950' : 'text-slate-400 hover:bg-slate-800 hover:text-slate-100'"
        >{{ unit }}</button>
      </div>
      <button
        type="button"
        @click="$emit('refresh')"
        :disabled="loading"
        title="Replay the wallet cache and refresh fiat quotes (does not rescan the wallet)"
        class="rounded-lg border border-slate-700 bg-slate-800 px-3.5 py-2 text-sm text-slate-100 transition hover:border-accent disabled:opacity-50"
      >{{ loading ? 'Loading…' : '↻ Refresh' }}</button>
    </div>
  </header>
</template>