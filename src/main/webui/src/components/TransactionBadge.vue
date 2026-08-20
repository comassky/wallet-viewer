<script setup lang="ts">
import type { TxType } from '../types/wallet';
import { transactionClasses, transactionLabels } from '../utils/format';

defineProps<{ type: TxType }>();
</script>

<template>
  <span class="rounded-full px-2 py-0.5 text-xs" :class="transactionClasses[type]">{{ transactionLabels[type] }}</span>
</template>