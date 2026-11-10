export type DiffLine = { op: "same" | "add" | "del"; text: string } | { op: "gap"; count: number };

/**
 * Diff por líneas (LCS). Los documentos son pequeños; si fueran enormes, devuelve null y la
 * interfaz muestra los dos textos lado a lado. Las zonas iguales largas se resumen.
 */
export function lineDiff(from: string, to: string, context = 3): DiffLine[] | null {
  const a = from.split("\n");
  const b = to.split("\n");
  if (a.length * b.length > 4_000_000) return null;
  const lcs: Uint32Array[] = Array.from({ length: a.length + 1 }, () => new Uint32Array(b.length + 1));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const raw: { op: "same" | "add" | "del"; text: string }[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      raw.push({ op: "same", text: a[i++] });
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      raw.push({ op: "del", text: a[i++] });
    } else {
      raw.push({ op: "add", text: b[j++] });
    }
  }
  while (i < a.length) raw.push({ op: "del", text: a[i++] });
  while (j < b.length) raw.push({ op: "add", text: b[j++] });

  // Resume las zonas sin cambios dejando `context` líneas alrededor de cada cambio.
  const keep = raw.map(() => false);
  raw.forEach((line, k) => {
    if (line.op === "same") return;
    for (let d = Math.max(0, k - context); d <= Math.min(raw.length - 1, k + context); d++) keep[d] = true;
  });
  const out: DiffLine[] = [];
  let hidden = 0;
  raw.forEach((line, k) => {
    if (keep[k]) {
      if (hidden) out.push({ op: "gap", count: hidden });
      hidden = 0;
      out.push(line);
    } else {
      hidden++;
    }
  });
  if (hidden) out.push({ op: "gap", count: hidden });
  return out;
}
