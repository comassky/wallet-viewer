<script setup lang="ts">
import type { TxType } from '@/types/wallet';
import { transactionClasses, transactionDotClasses, transactionLabels } from '@/utils/format';
import AppTooltip from '@/components/AppTooltip.vue';

defineProps<{ type: TxType }>();
</script>

<template>
  <AppTooltip :text="type === 'self' ? 'All input and output addresses belong to this wallet. Only the transaction fee leaves the wallet.' : undefined" class="transaction-badge-tooltip">
  <span class="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium" :class="transactionClasses[type]">
    <span class="h-1.5 w-1.5 rounded-full" :class="transactionDotClasses[type]" aria-hidden="true" />{{ transactionLabels[type] }}
  </span>
  </AppTooltip>
</template>

<style>
@media (min-width: 1024px) {
  .transaction-badge-tooltip [role='tooltip'] {
    left: 0;
    translate: 0;
  }

  .transaction-badge-tooltip [role='tooltip'] > [aria-hidden] {
    left: 20px;
  }
}

button:focus-visible .transaction-badge-tooltip [role='tooltip'] {
  visibility: visible;
  opacity: 1;
  scale: 1;
}
</style>