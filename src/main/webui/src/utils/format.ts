import type { TxType } from '../types/wallet';

export function shortId(id: string): string {
  return id ? `${id.slice(0, 10)}…${id.slice(-6)}` : '';
}

const dateFormat = new Intl.DateTimeFormat(navigator.language, {
  year: 'numeric', month: 'numeric', day: 'numeric',
  hour: 'numeric', minute: 'numeric', second: 'numeric',
});

export function formatDate(timestamp: number | null): string {
  return timestamp ? dateFormat.format(timestamp * 1000) : '—';
}

export const transactionLabels = {
  received: 'Received', sent: 'Sent', self: 'Self-transfer',
} as const satisfies Record<TxType, string>;

export const transactionClasses = {
  received: 'border-emerald-500/20 bg-emerald-500/10 text-emerald-300',
  sent: 'border-rose-500/20 bg-rose-500/10 text-rose-300',
  self: 'border-sky-500/20 bg-sky-500/10 text-sky-300',
} as const satisfies Record<TxType, string>;

export const transactionDotClasses = {
  received: 'bg-emerald-400',
  sent: 'bg-rose-400',
  self: 'bg-sky-400',
} as const satisfies Record<TxType, string>;