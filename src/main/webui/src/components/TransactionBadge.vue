<script setup lang="ts">
import type { TxType } from '@/types/wallet';
import { transactionClasses, transactionDotClasses, transactionLabels } from '@/utils/format';

defineProps<{ type: TxType }>();
</script>

<template>
  <span class="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-medium" :class="transactionClasses[type]">
    <span class="h-1.5 w-1.5 rounded-full" :class="transactionDotClasses[type]" aria-hidden="true" />{{ transactionLabels[type] }}
  </span>
</template>