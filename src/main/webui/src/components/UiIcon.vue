<script setup lang="ts">
import {
  mdiShieldCheck, mdiServer, mdiPulse, mdiCashMultiple, mdiContentCopy, mdiCheck, mdiClose,
  mdiChevronLeft, mdiChevronRight, mdiQrcode, mdiMagnify, mdiCheckCircleOutline, mdiAlertOutline,
  mdiGraphOutline, mdiFormatListBulleted, mdiArrowRight, mdiInboxOutline, mdiGithub,
  mdiChartLine, mdiEyeOutline, mdiEyeOffOutline, mdiBellOutline,
} from '@mdi/js';

type IconName = 'shield' | 'server' | 'activity' | 'coins' | 'copy' | 'check' | 'close' | 'chevron-left' | 'chevron-right' | 'qr-code' | 'search' | 'circle-check' | 'alert' | 'graph' | 'list' | 'arrow-right' | 'inbox' | 'github' | 'chart' | 'eye' | 'eye-off' | 'bell';
defineProps<{ name: IconName }>();
// Single icon source: Material Design Icons paths, tree-shaken via named imports.
const paths: Record<IconName, string> = {
  shield: mdiShieldCheck, server: mdiServer,
  activity: mdiPulse, coins: mdiCashMultiple, copy: mdiContentCopy, check: mdiCheck, close: mdiClose,
  'chevron-left': mdiChevronLeft, 'chevron-right': mdiChevronRight,
  'qr-code': mdiQrcode, search: mdiMagnify, 'circle-check': mdiCheckCircleOutline, alert: mdiAlertOutline,
  graph: mdiGraphOutline, list: mdiFormatListBulleted, 'arrow-right': mdiArrowRight, inbox: mdiInboxOutline,
  github: mdiGithub, chart: mdiChartLine, eye: mdiEyeOutline, 'eye-off': mdiEyeOffOutline, bell: mdiBellOutline,
};
</script>

<template>
  <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" fill="currentColor" class="h-4 w-4 shrink-0"><path :d="paths[name]" /></svg>
</template>