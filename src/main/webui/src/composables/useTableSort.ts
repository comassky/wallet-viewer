import { computed, ref, type Ref } from 'vue';

export type SortValue = string | number | null;
export interface SortColumn<T> {
  key: string;
  label: string;
  value: (row: T) => SortValue;
  secondary?: (row: T) => SortValue;
  numeric?: boolean;
}

function compare(a: SortValue, b: SortValue, descending: boolean): number {
  // Unknown dates stay last regardless of the direction.
  if (a === null) return b === null ? 0 : 1;
  if (b === null) return -1;
  const result = typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b));
  return descending ? -result : result;
}

export function useTableSort<T>(rows: Ref<T[]>, columns: SortColumn<T>[]) {
  const sortKey = ref('');
  const descending = ref(false);
  const sorted = computed(() => {
    const column = columns.find(item => item.key === sortKey.value);
    if (!column) return rows.value;
    return [...rows.value].sort((a, b) => compare(column.value(a), column.value(b), descending.value)
      || (column.secondary ? compare(column.secondary(a), column.secondary(b), descending.value) : 0));
  });
  function toggleSort(key: string): void {
    if (key === sortKey.value) descending.value = !descending.value;
    else { sortKey.value = key; descending.value = false; }
  }
  function ariaSort(key: string): 'none' | 'ascending' | 'descending' {
    return key === sortKey.value ? (descending.value ? 'descending' : 'ascending') : 'none';
  }
  return { sorted, sortKey, descending, toggleSort, ariaSort };
}