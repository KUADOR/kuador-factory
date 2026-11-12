"use client";

import { Download, Search } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, useTransition } from "react";

import { auditPageAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { TextInput } from "@/components/ui/Field";
import { Badge, EmptyState } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import { auditQuery } from "@/lib/audit";
import type { AuditItem, AuditPage } from "@/lib/types";

const WHEN = new Intl.DateTimeFormat("es-EC", { dateStyle: "medium", timeStyle: "short" });
const OUTCOME: Record<AuditItem["outcome"], { label: string; tone: "ok" | "danger" | "neutral" }> = {
  OK: { label: "Hecho", tone: "ok" },
  DENIED: { label: "Denegado", tone: "danger" },
  FAILED: { label: "Falló", tone: "neutral" },
};
const PERIODS = [
  { value: "7", label: "7 días" },
  { value: "30", label: "30 días" },
  { value: "90", label: "90 días" },
  { value: "todo", label: "Todo" },
];

/**
 * La auditoría: quién hizo qué, cuándo, desde dónde y cómo terminó. Por defecto, solo lo de seguridad (accesos,
 * permisos, claves, conexiones, pagos); «Todo» suma el trabajo diario.
 */
export function AuditView({
  page,
  scope,
  workspaces,
  exportHref,
}: {
  page: AuditPage;
  scope: "workspace" | "platform";
  /** En la plataforma: nombre de cada espacio. */
  workspaces?: Record<string, string>;
  exportHref?: string;
}) {
  const router = useRouter();
  const params = useSearchParams();
  const [items, setItems] = useState(page.items);
  const [next, setNext] = useState(page.next);
  const [text, setText] = useState(params.get("q") ?? "");
  const [pending, start] = useTransition();
  const ver = params.get("ver") ?? "seguridad";
  const desde = params.get("desde") ?? "30";

  const go = (patch: Record<string, string | null>) => {
    const q = new URLSearchParams(params.toString());
    for (const [k, v] of Object.entries(patch)) {
      if (v) q.set(k, v);
      else q.delete(k);
    }
    router.replace(`?${q.toString()}`, { scroll: false });
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <div role="tablist" aria-label="Qué mostrar" className="flex rounded-md border border-line p-0.5">
          {[
            { value: "seguridad", label: "Seguridad" },
            { value: "todo", label: "Todo" },
          ].map((t) => (
            <button
              key={t.value}
              role="tab"
              aria-selected={ver === t.value}
              onClick={() => go({ ver: t.value === "seguridad" ? null : t.value })}
              className={ver === t.value ? "rounded bg-selected px-3 py-1 text-[13px] font-medium text-fg-strong" : "rounded px-3 py-1 text-[13px] text-fg-muted hover:text-fg-strong"}
            >
              {t.label}
            </button>
          ))}
        </div>
        <select
          aria-label="Periodo"
          value={desde}
          onChange={(e) => go({ desde: e.target.value === "30" ? null : e.target.value })}
          className="h-8 rounded-md border border-line bg-surface px-2 text-[13px] text-fg"
        >
          {PERIODS.map((p) => (
            <option key={p.value} value={p.value}>
              {p.label}
            </option>
          ))}
        </select>
        <form
          className="flex min-w-48 flex-1 items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            go({ q: text.trim() || null });
          }}
        >
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-fg-faint" aria-hidden />
            <TextInput aria-label="Buscar" value={text} onChange={(e) => setText(e.target.value)} placeholder="Persona, acción, tarjeta o IP" className="pl-8" />
          </div>
        </form>
        {exportHref && (
          <Button size="sm" icon={Download} href={`${exportHref}?${params.toString()}`}>
            Exportar CSV
          </Button>
        )}
      </div>

      {items.length === 0 ? (
        <EmptyState title="Nada en este periodo">Prueba con otro periodo o con «Todo».</EmptyState>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-line bg-surface">
          <table className="w-full min-w-[44rem] text-left text-[13px]">
            <thead className="border-b border-line bg-sunken text-xs text-fg-muted">
              <tr>
                <th scope="col" className="px-4 py-2 font-medium">Cuándo</th>
                {workspaces && <th scope="col" className="px-3 py-2 font-medium">Espacio</th>}
                <th scope="col" className="px-3 py-2 font-medium">Quién</th>
                <th scope="col" className="px-3 py-2 font-medium">Qué</th>
                <th scope="col" className="px-3 py-2 font-medium">Resultado</th>
                <th scope="col" className="px-4 py-2 font-medium">Desde</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {items.map((i) => (
                <tr key={i.id} className="align-top">
                  <td suppressHydrationWarning className="tabular px-4 py-2.5 whitespace-nowrap text-fg-muted">{WHEN.format(new Date(i.occurredAt))}</td>
                  {workspaces && <td className="px-3 py-2.5 text-fg-muted">{i.workspaceId ? (workspaces[i.workspaceId] ?? "—") : "Sin espacio"}</td>}
                  <td className="px-3 py-2.5 text-fg">
                    {i.actorName ?? (i.actorKind === "ANONYMOUS" ? "Sin sesión" : "—")}
                    {i.actorKind === "AGENT" && <span className="ml-1.5 text-xs text-fg-faint">agente</span>}
                  </td>
                  <td className="px-3 py-2.5">
                    <span className="text-fg-strong">{i.label}</span>
                    {i.target && <span className="block truncate text-xs text-fg-muted">{i.target}</span>}
                  </td>
                  <td className="px-3 py-2.5">
                    <Badge tone={OUTCOME[i.outcome].tone}>{OUTCOME[i.outcome].label}</Badge>
                  </td>
                  <td className="px-4 py-2.5 font-mono text-xs text-fg-muted" title={i.userAgent ?? undefined}>
                    {i.ip ?? "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {next && (
        <div className="flex justify-center">
          <Button
            loading={pending}
            onClick={() =>
              start(async () => {
                const r = await auditPageAction(scope, auditQuery({ ver, q: params.get("q") ?? "", desde }, { before: next, ...(params.get("espacio") ? { workspace: params.get("espacio")! } : {}) }));
                if (!r.ok) {
                  notify.error("No se pudo cargar", r.error);
                  return;
                }
                setItems((prev) => [...prev, ...r.data.items]);
                setNext(r.data.next);
              })
            }
          >
            Ver más
          </Button>
        </div>
      )}
    </div>
  );
}
