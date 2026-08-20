import type { TxType } from '../types/wallet';

export function shortId(id: string): string {
  return id ? `${id.slice(0, 10)}…${id.slice(-6)}` : '';
}

export function formatDate(timestamp: number | null): string {
  return timestamp ? new Date(timestamp * 1000).toLocaleString('en-US') : '—';
}

export const transactionLabels: Record<TxType, string> = {
  received: 'Received', sent: 'Sent', self: 'Self-transfer',
};

export const transactionClasses: Record<TxType, string> = {
  received: 'bg-emerald-500/15 text-emerald-400',
  sent: 'bg-rose-500/15 text-rose-400',
  self: 'bg-sky-500/15 text-sky-400',
};