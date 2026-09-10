<template>
  <section aria-busy="true" aria-label="Wallet synchronization">
    <p role="status" class="mb-5 text-sm text-slate-400">Synchronizing your wallet…</p>
    <div aria-hidden="true" class="motion-safe:animate-pulse">
      <div class="grid gap-5 lg:grid-cols-2">
        <div class="wallet-panel p-6 sm:p-8">
          <div class="h-4 w-32 rounded bg-slate-800" />
          <div class="mt-6 h-12 w-3/4 rounded-lg bg-slate-800" />
          <div class="mt-3 h-5 w-1/2 rounded bg-slate-800" />
          <div class="mt-6 grid grid-cols-2 gap-6 border-t border-slate-800 pt-5"><div class="h-12 rounded bg-slate-800" /><div class="h-12 rounded bg-slate-800" /></div>
        </div>
        <div class="wallet-panel hidden p-8 lg:block"><div class="h-5 w-40 rounded bg-slate-800" /><div class="mt-6 flex items-center gap-5"><div class="h-36 w-36 shrink-0 rounded-xl bg-slate-800" /><div class="h-20 flex-1 rounded-lg bg-slate-800" /></div></div>
      </div>
      <div class="mt-5 h-11 w-full rounded-lg bg-slate-800 lg:hidden" />
      <div class="mb-5 mt-9 h-10 w-52 rounded-lg bg-slate-800" />
      <div class="wallet-panel space-y-5 p-6"><div v-for="row in 4" :key="row" class="h-12 rounded-lg bg-slate-800" /></div>
    </div>
  </section>
</template>