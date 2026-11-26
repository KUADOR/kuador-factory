"use client";

import { ArrowDown, ArrowRight, CalendarClock, ListOrdered, Lock, Mail, Pencil, Plug, User, Webhook } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { clsx } from "clsx";

import { Button } from "@/components/ui/Button";
import { SidePanel } from "@/components/ui/SidePanel";
import type { MailInbox, Project, Routine } from "@/lib/types";

import { LoopBrake } from "./LoopBrake";
import { MoveRules } from "./MoveRules";
import { StepEditor, type Option } from "./StepEditor";
import { StepsEditor } from "./StepsEditor";

/**
 * El flujo del tablero y el único lugar donde se cambia: los pasos (agregar, ordenar, quitar), cada paso (quién lo
 * hace, quién aprueba, qué exige) y, al final, por dónde puede moverse una tarjeta.
 */
export function FlowView({
  project,
  counts,
  agents,
  inboxes,
  routines,
  connections,
  roleOptions,
  humanRoleOptions,
  documentOptions,
  roleName,
  canEdit,
}: {
  project: Project;
  counts: Record<string, number>;
  agents: { name: string; role: string }[];
  inboxes: MailInbox[];
  routines: Routine[];
  connections: { key: string; name: string; roles: string[]; writes: string[] }[];
  roleOptions: Option[];
  humanRoleOptions: Option[];
  documentOptions: Option[];
  roleName: Record<string, string>;
  canEdit: boolean;
}) {
  const router = useRouter();
  const [editing, setEditing] = useState<string | null>(null);
  const [editingSteps, setEditingSteps] = useState(false);
  const columns = project.columns;
  const stageOf = (key: string) => project.stages.find((s) => s.column === key);
  const base = `/projects/${project.key}`;

  const responsible = (key: string, index: number) => {
    const st = stageOf(key);
    if (st?.gate) return { icon: Lock, text: st.approvers.length ? `Aprueba ${st.approvers.map((a) => roleName[a] ?? a).join(" o ")}` : "Aprueba una persona", tone: "gate" as const };
    if (st && st.roles.length > 0) {
      const names = st.roles.map((r) => agents.find((a) => a.role === r)?.name ?? roleName[r] ?? r);
      return { icon: null, text: names.join(", "), tone: "agent" as const };
    }
    if (index === 0) return { icon: null, text: "Entra el trabajo", tone: "edge" as const };
    if (columns[index].category === "DONE") return { icon: null, text: "Terminado", tone: "edge" as const };
    return { icon: User, text: "Una persona", tone: "person" as const };
  };

  const systemsFor = (key: string) => {
    const st = stageOf(key);
    if (!st || st.gate || st.roles.length === 0) return [];
    return connections.filter((c) => c.roles.length === 0 || c.roles.some((r) => st.roles.includes(r)));
  };

  const entries = [
    ...inboxes.map((b) => ({ icon: Mail, title: b.connectionName, detail: [b.enabled ? null : "en pausa", b.fromFilter ? `de ${b.fromFilter}` : null, b.onlyAttachments ? "con adjuntos" : null].filter(Boolean).join(" · ") || "cada correo nuevo", href: `${base}/settings/intake#correo` })),
    ...routines.map((r) => ({ icon: r.webhook ? Webhook : CalendarClock, title: r.title, detail: r.enabled ? r.schedule : "en pausa", href: `${base}/settings/intake#rutinas` })),
  ];
  const outputs = connections.flatMap((c) => c.writes.map((w) => ({ name: w, system: c.name })));

  return (
    <div className="flex flex-col gap-6 px-4 py-5 sm:px-6 sm:py-6">
      <section className="flex flex-col gap-2">
        <h2 className="text-xs font-medium text-fg-muted">Entradas</h2>
        {entries.length === 0 ? (
          <p className="text-[13px] text-fg-faint">
            El trabajo entra a mano.{" "}
            {canEdit && (
              <Link href={`${base}/settings/intake#correo`} className="k-link">
                Recibir correos como tarjetas
              </Link>
            )}
          </p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {entries.map((e) => (
              <Link key={e.title + e.href} href={e.href} className="flex items-center gap-2 rounded-lg border border-line bg-surface px-3 py-2 text-[13px] hover:bg-hover">
                <e.icon className="size-4 shrink-0 text-sol-text" aria-hidden />
                <span className="min-w-0">
                  <span className="block font-medium text-fg-strong">{e.title}</span>
                  <span className="block text-xs text-fg-muted">{e.detail}</span>
                </span>
              </Link>
            ))}
          </div>
        )}
      </section>

      <section className="flex flex-col gap-2">
        <div className="flex items-center gap-3">
          <h2 className="text-xs font-medium text-fg-muted">Pasos</h2>
          {canEdit && (
            <Button size="sm" tone="ghost" icon={ListOrdered} onClick={() => setEditingSteps(true)}>
              Agregar, ordenar o quitar pasos
            </Button>
          )}
        </div>
        <ol className="flex flex-col gap-1 lg:flex-row lg:items-stretch lg:gap-0 lg:overflow-x-auto lg:pb-2">
          {columns.map((c, i) => {
            const st = stageOf(c.key);
            const who = responsible(c.key, i);
            const systems = systemsFor(c.key);
            const ways = project.transitions.allowed.filter((t) => t.from === c.key && t.when);
            const Icon = who.icon;
            return (
              <li key={c.key} className="flex flex-col items-center lg:flex-row">
                <button
                  type="button"
                  disabled={!canEdit || i === 0 || c.category === "DONE"}
                  onClick={() => setEditing(c.key)}
                  className={clsx(
                    "flex w-full flex-col gap-1.5 rounded-lg border bg-surface p-3 text-left lg:w-56 lg:shrink-0",
                    who.tone === "gate" ? "border-sol/50" : "border-line",
                    canEdit && i > 0 && c.category !== "DONE" ? "hover:border-line-strong hover:bg-hover" : "cursor-default",
                  )}
                >
                  <span className="flex items-baseline gap-2">
                    <span className="min-w-0 flex-1 text-[13px] font-medium text-fg-strong">{c.name}</span>
                    <span className="tabular rounded-full bg-hover px-1.5 text-[11px] text-fg-muted" title="Tarjetas en este paso">
                      {counts[c.key] ?? 0}
                    </span>
                  </span>
                  <span className={clsx("flex items-center gap-1 text-xs", who.tone === "agent" ? "text-fg" : "text-fg-muted")}>
                    {Icon && <Icon className="size-3 shrink-0" aria-hidden />}
                    {who.text}
                  </span>
                  {st?.instructions && <span className="line-clamp-3 text-xs text-fg-muted">{st.instructions}</span>}
                  {systems.length > 0 && (
                    <span className="flex flex-wrap gap-1">
                      {systems.map((s) => (
                        <span key={s.key} className="inline-flex items-center gap-1 rounded bg-hover px-1.5 py-px text-[11px] text-fg-muted">
                          <Plug className="size-3" aria-hidden />
                          {s.name}
                        </span>
                      ))}
                    </span>
                  )}
                  {(st?.checklist ?? []).length > 0 && <span className="text-[11px] text-fg-faint">Comprueba {(st?.checklist ?? []).length} punto(s)</span>}
                  {ways.length > 0 && (
                    <span className="flex flex-col gap-0.5 border-t border-line pt-1.5 text-[11px] text-fg-muted">
                      {ways.map((w) => {
                        const target = columns.find((x) => x.key === w.to);
                        const back = columns.findIndex((x) => x.key === w.to) < i;
                        return (
                          <span key={w.to}>
                            {back ? "↺" : "↳"} {w.when} → «{target?.name ?? w.to}»
                          </span>
                        );
                      })}
                      {columns[i + 1] && !ways.some((w) => w.to === columns[i + 1].key) && <span>↳ en otro caso → «{columns[i + 1].name}»</span>}
                    </span>
                  )}
                  {canEdit && i > 0 && c.category !== "DONE" && (
                    <span className="mt-auto flex items-center gap-1 pt-1 text-[11px] text-fg-faint">
                      <Pencil className="size-3" aria-hidden /> Editar
                    </span>
                  )}
                </button>
                {i < columns.length - 1 && (
                  <span className="flex items-center justify-center p-1 text-fg-faint" aria-hidden>
                    <ArrowDown className="size-4 lg:hidden" />
                    <ArrowRight className="hidden size-4 lg:block" />
                  </span>
                )}
              </li>
            );
          })}
        </ol>
      </section>

      <section className="flex flex-col gap-2">
        <h2 className="text-xs font-medium text-fg-muted">Salidas</h2>
        {outputs.length === 0 ? (
          <p className="text-[13px] text-fg-faint">Los agentes no cambian datos en otros sistemas: preparan y una persona hace.</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {outputs.map((o) => (
              <span key={o.system + o.name} className="flex items-center gap-2 rounded-lg border border-line bg-surface px-3 py-2 text-[13px]">
                <Plug className="size-4 shrink-0 text-sol-text" aria-hidden />
                <span>
                  <span className="font-mono text-xs text-fg-strong">{o.name}</span> <span className="text-fg-muted">en {o.system}</span>
                </span>
              </span>
            ))}
          </div>
        )}
      </section>

      {canEdit && (
        <section className="flex flex-col gap-2">
          <h2 className="text-xs font-medium text-fg-muted">Reglas de movimiento</h2>
          <details className="group">
            <summary className="cursor-pointer text-[13px] text-fg-muted hover:text-fg-strong">
              {project.transitions.restricted ? "Las tarjetas solo se mueven por los caminos fijados." : "Las tarjetas se mueven libremente entre pasos."} <span className="k-link">Cambiar</span>
            </summary>
            <div className="mt-3 flex max-w-4xl flex-col gap-4">
              <MoveRules project={project} />
              <LoopBrake project={project} />
            </div>
          </details>
        </section>
      )}

      <SidePanel open={editing != null} onClose={() => setEditing(null)} width="narrow" title={columns.find((c) => c.key === editing)?.name ?? "Paso"}>
        {editing && (
          <StepEditor
            key={editing}
            project={project}
            column={editing}
            roleOptions={roleOptions}
            humanRoleOptions={humanRoleOptions}
            documentOptions={documentOptions}
            onDone={() => setEditing(null)}
          />
        )}
      </SidePanel>
      <SidePanel open={editingSteps} onClose={() => setEditingSteps(false)} title="Pasos del tablero">
        {editingSteps && (
          <StepsEditor
            project={project}
            counts={counts}
            onSaved={() => {
              setEditingSteps(false);
              router.refresh();
            }}
          />
        )}
      </SidePanel>
    </div>
  );
}
