<script setup lang="ts">
defineProps<{ loading: boolean }>();
defineEmits<{ refresh: [] }>();
</script>

<template>
  <header class="mb-8 flex flex-wrap items-center justify-between gap-4">
    <div class="flex items-center gap-3.5">
      <span class="flex h-12 w-12 items-center justify-center rounded-2xl border border-accent/30 bg-accent/10 text-3xl text-accent" aria-hidden="true">₿</span>
      <div>
        <h1 class="text-xl font-semibold tracking-tight sm:text-2xl">Wallet <span class="font-normal text-slate-400">Viewer</span></h1>
        <p class="mt-0.5 text-xs tracking-wide text-slate-500">Your Bitcoin. A clearer picture.</p>
      </div>
    </div>
    <div class="flex items-center gap-3">
      <span class="hidden rounded-full border border-slate-700/60 px-3 py-1.5 text-[11px] uppercase tracking-widest text-slate-400 sm:inline-flex">Read-only wallet</span>
      <button
        type="button"
        @click="$emit('refresh')"
        :disabled="loading"
        title="Replay the wallet cache and refresh fiat quotes (does not rescan the wallet)"
        class="button-secondary rounded-xl px-4 py-2 text-sm disabled:opacity-50"
      ><span aria-hidden="true">↻</span> {{ loading ? 'Loading…' : 'Refresh' }}</button>
    </div>
  </header>
</template>