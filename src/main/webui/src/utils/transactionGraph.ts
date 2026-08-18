/** Deterministic bipartite layout: every input/output has one edge to the transaction. */
export function transactionGraphLayout(inputCount: number, outputCount: number) {
  const height = Math.max(240, Math.max(inputCount, outputCount) * 72 + 32);
  const positions = (count: number) => Array.from({ length: count }, (_, index) =>
    (height - count * 72) / 2 + index * 72 + 36);
  const centerY = height / 2;
  return {
    width: 960,
    height,
    centerY,
    inputs: positions(inputCount).map(y => ({ y, path: `M 288 ${y} C 354 ${y}, 354 ${centerY}, 420 ${centerY}` })),
    outputs: positions(outputCount).map(y => ({ y, path: `M 540 ${centerY} C 606 ${centerY}, 606 ${y}, 672 ${y}` })),
  };
}