"use client";

import { GitCompare, History, RotateCcw } from "lucide-react";
import { useEffect, useState, useTransition } from "react";
import { clsx } from "clsx";

import { catalogRevisionAction, catalogRevisionsAction, restoreCatalogRevisionAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { notify } from "@/components/ui/Toaster";
import type { CatalogRevision, CatalogType } from "@/lib/types";

/** Lo comparable de una entrada: sin id, versión ni origen, con cada campo en sus propias líneas. */
function lines(entry: unknown): string[] {
  const { id: _id, version: _v, builtIn: _b, ...rest } = (entry ?? {}) as Record<string, unknown>;
  void _id;
  void _v;
  void _b;
  return JSON.stringify(rest, null, 2)
    .split("\n")
    .flatMap((l) => l.replace(/\\n/g, "\n").split("\n"));
}

/** Diferencia por líneas (la más larga en común): lo que quitar (−) y lo que agregar (+) para pasar de una a otra. */
function diff(before: string[], after: string[]): { kind: " " | "-" | "+"; text: string }[] {
  const n = before.length;
  const m = after.length;
  const lcs = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) lcs[i][j] = before[i] === after[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
  const out: { kind: " " | "-" | "+"; text: string }[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (before[i] === after[j]) {
      out.push({ kind: " ", text: before[i++] });
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) out.push({ kind: "-", text: before[i++] });
    else out.push({ kind: "+", text: after[j++] });
  }
  while (i < n) out.push({ kind: "-", text: before[i++] });
  while (j < m) out.push({ kind: "+", text: after[j++] });
  return out;
}

/**
 * El historial de una entrada del catálogo: cada versión con quién la guardó y cuándo, compararla con la actual y volver
 * a ella. Así quien administra mejora los manuales de sus agentes sin miedo: si un cambio empeora al agente, se deshace.
 */
export function EntryHistory({ type, entryKey, current, onRestored }: { type: CatalogType; entryKey: string; current: unknown; onRestored: () => void }) {
  const [revisions, setRevisions] = useState<CatalogRevision[] | null>(null);
  const [comparing, setComparing] = useState<{ id: string; rows: ReturnType<typeof diff> } | null>(null);
  const [pending, start] = useTransition();

  useEffect(() => {
    let alive = true;
    catalogRevisionsAction(type, entryKey).then((r) => alive && setRevisions(r.ok ? r.data : []));
    return () => {
      alive = false;
    };
  }, [type, entryKey]);

  if (revisions == null) return <p className="text-xs text-fg-faint">Cargando historial…</p>;
  if (revisions.length <= 1) return <p className="text-xs text-fg-faint">Todavía no hay versiones anteriores: aparecen al guardar cambios.</p>;

  return (
    <div className="flex flex-col gap-3">
      <ul className="divide-y divide-line rounded-md border border-line">
        {revisions.map((r, i) => (
          <li key={r.id} className="flex flex-wrap items-center gap-x-3 gap-y-1.5 px-3 py-2 text-[13px]">
            <History className="size-3.5 shrink-0 text-fg-faint" aria-hidden />
            <span className="min-w-0 flex-1">
              <span className="text-fg-strong">{new Date(r.savedAt).toLocaleString("es", { dateStyle: "medium", timeStyle: "short" })}</span>
              <span className="text-xs text-fg-muted"> · {r.savedBy ?? "de fábrica"}</span>
              {i === 0 && <span className="ml-1.5 rounded bg-sol/15 px-1.5 py-0.5 text-[10px] font-medium text-sol-text">actual</span>}
            </span>
            {i > 0 && (
              <span className="flex gap-1.5">
                <Button
                  size="sm"
                  tone="ghost"
                  icon={GitCompare}
                  disabled={pending}
                  onClick={() =>
                    start(async () => {
                      if (comparing?.id === r.id) return setComparing(null);
                      const res = await catalogRevisionAction(type, entryKey, r.id);
                      if (res.ok) setComparing({ id: r.id, rows: diff(lines(res.data), lines(current)) });
                      else notify.error("No se pudo abrir esa versión", res.error);
                    })
                  }
                >
                  {comparing?.id === r.id ? "Ocultar" : "Comparar"}
                </Button>
                <Button
                  size="sm"
                  tone="ghost"
                  icon={RotateCcw}
                  disabled={pending}
                  onClick={() =>
                    start(async () => {
                      if (!window.confirm("¿Volver a esta versión? La actual queda en el historial: puedes regresar cuando quieras.")) return;
                      const res = await restoreCatalogRevisionAction(type, entryKey, r.id);
                      if (res.ok) {
                        notify.ok("Listo: volviste a esa versión", "Los agentes la usan desde su próxima tarjeta.");
                        onRestored();
                      } else notify.error("No se pudo volver", res.error);
                    })
                  }
                >
                  Volver a esta versión
                </Button>
              </span>
            )}
          </li>
        ))}
      </ul>
      {comparing && (
        <div className="overflow-hidden rounded-md border border-line">
          <p className="border-b border-line bg-sunken px-3 py-1.5 text-xs text-fg-muted">
            <span className="text-danger">− así era</span> · <span className="text-ok">+ así está ahora</span>
          </p>
          <pre className="max-h-96 overflow-auto px-3 py-2 font-mono text-[11.5px] leading-relaxed">
            {comparing.rows
              .filter((row, idx, all) => row.kind !== " " || all.slice(Math.max(0, idx - 2), idx + 3).some((x) => x.kind !== " "))
              .map((row, idx) => (
                <div key={idx} className={clsx(row.kind === "-" && "bg-danger/10 text-danger", row.kind === "+" && "bg-ok/10 text-ok", row.kind === " " && "text-fg-muted")}>
                  {row.kind} {row.text}
                </div>
              ))}
          </pre>
        </div>
      )}
    </div>
  );
}
