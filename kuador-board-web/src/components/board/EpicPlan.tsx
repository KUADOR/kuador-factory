"use client";

import { Check, RotateCcw } from "lucide-react";
import { useEffect, useState, useTransition } from "react";

import { listEpicTasksAction, reviewPlanAction } from "@/app/actions";
import { useLiveVersion } from "@/components/live/LiveUpdates";
import { useAccess } from "@/components/shell/AccessContext";
import { Button } from "@/components/ui/Button";
import { TextArea } from "@/components/ui/Field";
import { Badge } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { EpicTask, Issue, Project } from "@/lib/types";

import { stageOf } from "./stages";

/**
 * Reparto de tareas de una épica: qué tarea hace cada rol y qué espera a qué. Cuando la épica llega a una etapa de
 * trabajo, nada arranca hasta que una persona apruebe el reparto o pida cambios al arquitecto.
 */
export function EpicPlan({ epic, project, roleName, onOpen }: { epic: Issue; project: Project; roleName: (key: string) => string; onOpen?: (key: string) => void }) {
  const live = useLiveVersion(epic.key);
  const { canEdit } = useAccess();
  const [tasks, setTasks] = useState<EpicTask[] | null>(null);
  const [changing, setChanging] = useState(false);
  const [reason, setReason] = useState("");
  const [pending, start] = useTransition();

  useEffect(() => {
    let active = true;
    listEpicTasksAction(epic.key).then((r) => active && r.ok && setTasks(r.data));
    return () => {
      active = false;
    };
  }, [epic.key, epic.status, live]);

  if (!tasks || tasks.length === 0) return null;
  const first = project.columns[0]?.key;
  const waiting = tasks.filter((t) => t.status === first);
  const stage = stageOf(project, epic.status);
  const awaiting = waiting.length > 0 && !!stage && !stage.gate && stage.roles.length > 0 && !epic.assigneeId;
  const done = new Set(project.columns.filter((c) => c.category === "DONE").map((c) => c.key));
  const finished = tasks.filter((t) => done.has(t.status)).length;

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
      {awaiting && canEdit && (
        <div className="flex flex-col gap-2 border-t border-line px-3.5 py-3">
          <p className="text-xs text-fg-muted">
            Al aprobar, cada tarea pasa a «{project.columns.find((c) => c.key === epic.status)?.name}» para el agente de su rol. Puedes editar o borrar tareas antes de aprobar.
          </p>
          {changing ? (
            <>
              <TextArea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Qué hay que cambiar en el reparto" aria-label="Cambios al reparto" />
              <div className="flex justify-end gap-2">
                <Button size="sm" tone="ghost" disabled={pending} onClick={() => setChanging(false)}>
                  Cancelar
                </Button>
                <Button size="sm" tone="secondary" icon={RotateCcw} loading={pending} disabled={reason.trim().length === 0} onClick={() => decide(false)}>
                  Devolver al arquitecto
                </Button>
              </div>
            </>
          ) : (
            <div className="flex justify-end gap-2">
              <Button size="sm" tone="ghost" icon={RotateCcw} disabled={pending} onClick={() => setChanging(true)}>
                Pedir cambios
              </Button>
              <Button size="sm" tone="primary" icon={Check} loading={pending} onClick={() => decide(true)}>
                Aprobar reparto
              </Button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
