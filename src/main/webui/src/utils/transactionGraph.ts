export const TRANSACTION_PAGE_SIZE = 5;

function nonNegativeInteger(value: number): number {
  return Number.isFinite(value) ? Math.max(0, Math.trunc(value)) : 0;
}

/** Zero-based page and half-open range; even an empty collection has page 1 of 1. */
export function paginationRange(total: number, requestedPage = 0, pageSize = TRANSACTION_PAGE_SIZE) {
  total = nonNegativeInteger(total);
  pageSize = Math.max(1, nonNegativeInteger(pageSize));
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const page = Math.min(nonNegativeInteger(requestedPage), pageCount - 1);
  const start = page * pageSize;
  const end = Math.min(start + pageSize, total);
  return { total, page, pageCount, pageSize, start, end, remaining: total - (end - start) };
}

/** Slice before mapping: rendering and allocations remain bounded for large transactions. */
export function paginateItems<T>(items: readonly T[], requestedPage = 0, pageSize = TRANSACTION_PAGE_SIZE) {
  const range = paginationRange(items.length, requestedPage, pageSize);
  return {
    ...range,
    entries: items.slice(range.start, range.end).map((item, offset) => ({ item, index: range.start + offset })),
  };
}

export interface ValueSummary {
  /** Never treat a missing/coinbase amount as zero when reporting a total. */
  total: number | null;
  knownTotal: number;
  unknownCount: number;
}

export function summarizeValues(items: readonly { value: number | null }[]): ValueSummary {
  let knownTotal = 0;
  let unknownCount = 0;
  for (const item of items) {
    if (item.value === null) unknownCount++;
    else knownTotal += item.value;
  }
  return { total: unknownCount ? null : knownTotal, knownTotal, unknownCount };
}

/** The group represents ALL off-page items, including earlier pages, in protocol order.
 * Cache the full summary at the call site so changing a page only visits five values.
 */
export function transactionGraphPage<T extends { value: number | null }>(
  items: readonly T[], requestedPage = 0, summary = summarizeValues(items),
) {
  const page = paginateItems(items, requestedPage);
  const visible = summarizeValues(page.entries.map(entry => entry.item));
  const knownTotal = summary.knownTotal - visible.knownTotal;
  const unknownCount = summary.unknownCount - visible.unknownCount;
  return {
    ...page,
    group: page.remaining ? {
      count: page.remaining,
      total: unknownCount ? null : knownTotal,
      knownTotal,
      unknownCount,
      nextPage: (page.page + 1) % page.pageCount,
    } : null,
  };
}

/** Counts are transaction sizes, not node counts. At most 5 leaves + 1 group per side.
 * HTML cards and decorative SVG share these coordinates; no input-to-output allocation.
 */
export function transactionGraphLayout(inputCount: number, outputCount: number, inputPage = 0, outputPage = 0) {
  const nodeCount = (total: number, page: number) => {
    const range = paginationRange(total, page);
    return range.end - range.start + (range.remaining ? 1 : 0);
  };
  const inputs = nodeCount(inputCount, inputPage);
  const outputs = nodeCount(outputCount, outputPage);
  const headerHeight = 104;
  const nodeHeight = 76;
  const rowHeight = nodeHeight + 10;
  const bodyHeight = Math.max(120, Math.max(inputs, outputs) * rowHeight + 12);
  const height = headerHeight + bodyHeight;
  const positions = (count: number) => Array.from({ length: count }, (_, index) =>
    headerHeight + (bodyHeight - count * rowHeight) / 2 + index * rowHeight + rowHeight / 2);
  const centerY = headerHeight + bodyHeight / 2;
  return {
    width: 1000,
    height,
    headerHeight,
    nodeHeight,
    centerY,
    inputs: positions(inputs).map(y => ({ y, path: `M 400 ${y} C 420 ${y}, 420 ${centerY}, 440 ${centerY}` })),
    outputs: positions(outputs).map(y => ({ y, path: `M 560 ${centerY} C 580 ${centerY}, 580 ${y}, 600 ${y}` })),
  };
}