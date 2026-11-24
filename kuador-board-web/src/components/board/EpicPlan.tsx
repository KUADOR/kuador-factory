"use client";

import { Check, RotateCcw } from "lucide-react";
import { useEffect, useState, useTransition } from "react";

import { epicPlanAction, reviewPlanAction } from "@/app/actions";
import { useLiveVersion } from "@/components/live/LiveUpdates";
import { useAccess } from "@/components/shell/AccessContext";
import { Button } from "@/components/ui/Button";
import { TextArea } from "@/components/ui/Field";
import { Badge } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { EpicPlanStatus, Issue, Member, Project } from "@/lib/types";

/**
 * Reparto de tareas de una épica: qué tarea hace cada rol y qué espera a qué. Cuando la épica llega a una etapa de
 * trabajo, nada arranca hasta que una persona apruebe el reparto o pida cambios al arquitecto. Si el reparto espera
 * aprobación lo dice la API, con la misma regla que aplica al aprobar: el botón aparece justo cuando se puede.
 */
export function EpicPlan({
  epic,
  project,
  roleName,
  onOpen,
  tasksVersion,
  ready = true,
  workingAgent,
  onLoaded,
}: {
  epic: Issue;
  project: Project;
  roleName: (key: string) => string;
  onOpen?: (key: string) => void;
  /** Cambia cuando el tablero ve tareas nuevas o movidas de esta épica: se vuelve a pedir el reparto. */
  tasksVersion?: string;
  /** Falta cargar algo del detalle (comentarios, GitHub): los botones esperan. */
  ready?: boolean;
  /** Agente que está trabajando la épica ahora: aprobar en medio de su trabajo lo pisaría. */
  workingAgent?: Member;
  /** Avisa que el reparto ya llegó (o falló), para habilitar los botones del detalle. */
  onLoaded?: () => void;
}) {
  const live = useLiveVersion(epic.key);
  const { canEdit } = useAccess();
  const [plan, setPlan] = useState<EpicPlanStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [changing, setChanging] = useState(false);
  const [reason, setReason] = useState("");
  const [pending, start] = useTransition();

  useEffect(() => {
    let active = true;
    epicPlanAction(epic.key).then((r) => {
      onLoaded?.();
      if (!active) return;
      if (r.ok) {
        setPlan(r.data);
        setError(null);
      } else setError(r.error);
    });
    return () => {
      active = false;
    };
  }, [epic.key, epic.status, live, tasksVersion, onLoaded]);

  if (error && !plan) return <p className="mb-4 text-xs text-danger">No se pudo cargar el reparto: {error}</p>;
  if (!plan) return <p className="mb-4 text-xs text-fg-faint">Cargando el reparto…</p>;
  const tasks = plan.tasks;
  if (tasks.length === 0) return null;
  const awaiting = plan.awaitingApproval;
  const done = new Set(project.columns.filter((c) => c.category === "DONE").map((c) => c.key));
  const finished = tasks.filter((t) => done.has(t.status)).length;
  const blocked = !ready ? "Cargando la tarjeta…" : workingAgent ? `${workingAgent.name} está trabajando en la épica: espera a que termine.` : undefined;

  const decide = (approve: boolean) =>
    start(async () => {
      const r = await reviewPlanAction(project.key, epic.key, approve, approve ? null : reason.trim());
      if (r.ok) {
        notify.ok(approve ? "Reparto aprobado: arrancan las tareas" : "Cambios pedidos al arquitecto");
        setChanging(false);
        setReason("");
      } else notify.error(approve ? "No se pudo aprobar el reparto" : "No se pudieron pedir cambios", r.error);
    });

  return (
    <section className="mb-4 rounded-md border border-line bg-sunken">
      <header className="flex flex-wrap items-center gap-2 border-b border-line px-3.5 py-2.5">
        <h3 className="text-[13px] font-semibold text-fg-strong">Reparto de tareas</h3>
        {awaiting ? <Badge tone="warn">Por aprobar</Badge> : <span className="tabular font-mono text-[11px] text-fg-muted">{finished}/{tasks.length} terminadas</span>}
      </header>
      <ol className="divide-y divide-line">
        {tasks.map((t, i) => (
          <li key={t.key} className="flex flex-wrap items-baseline gap-x-2 gap-y-1 px-3.5 py-2 text-[13px]">
            <span className="tabular w-5 shrink-0 font-mono text-[11px] text-fg-faint">{i + 1}.</span>
            <button type="button" onClick={() => onOpen?.(t.key)} className="min-w-0 flex-1 truncate text-left text-fg-strong hover:underline">
              {t.title}
            </button>
            <Badge tone="info">{roleName(t.workRole)}</Badge>
            <span className="text-xs text-fg-muted">{t.statusName}</span>
            {t.dependsOn.length > 0 && (
              <span className="basis-full pl-7 text-xs text-fg-faint">
                Después de {t.dependsOn.join(", ")}
                {t.pendingDependencies.length > 0 && !awaiting ? " · esperando" : ""}
              </span>
            )}
          </li>
        ))}
      </ol>
      {!awaiting && plan.notYet && (
        <p className="border-t border-line px-3.5 py-2.5 text-xs text-fg-muted">{plan.notYet}</p>
      )}
      {awaiting && canEdit && (
        <div className="flex flex-col gap-2 border-t border-line px-3.5 py-3">
          <p className="text-xs text-fg-muted">
            Al aprobar, cada tarea pasa a «{project.columns.find((c) => c.key === epic.status)?.name}» y la toma el agente de su rol.
          </p>
          {changing ? (
            <>
              <TextArea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Qué hay que cambiar en el reparto" aria-label="Cambios al reparto" />
              <div className="flex justify-end gap-2">
                <Button size="sm" tone="ghost" disabled={pending} onClick={() => setChanging(false)}>
                  Cancelar
                </Button>
                <Button size="sm" tone="secondary" icon={RotateCcw} loading={pending} disabled={reason.trim().length === 0 || !!blocked} title={blocked} onClick={() => decide(false)}>
                  Devolver al arquitecto
                </Button>
              </div>
            </>
          ) : (
            <div className="flex justify-end gap-2">
              <Button size="sm" tone="ghost" icon={RotateCcw} disabled={pending || !!blocked} title={blocked} onClick={() => setChanging(true)}>
                Pedir cambios
              </Button>
              <Button size="sm" tone="primary" icon={Check} loading={pending} disabled={!!blocked} title={blocked} onClick={() => decide(true)}>
                Aprobar reparto
              </Button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
