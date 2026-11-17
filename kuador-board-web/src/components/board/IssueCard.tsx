import { clsx } from "clsx";

import { WorkingLine } from "@/components/agents/WorkingLine";
import { Avatar } from "@/components/ui/Avatar";
import { PriorityIcon, TypeIcon } from "@/components/ui/IssueIcons";
import type { TypeSource } from "@/lib/labels";
import type { CardPlan, Issue, Member } from "@/lib/types";

/**
 * Tarjeta del tablero. Si un agente está trabajando el issue, lo dice en una línea viva: es la
 * forma de ver, sin abrir nada, qué hace cada agente del equipo.
 */
export function IssueCard({
  issue,
  assignee,
  epicTitle,
  projectType,
  workingAgent,
  waiting,
  plan,
  dragging = false,
}: {
  issue: Issue;
  assignee: Member | undefined;
  epicTitle: string | undefined;
  projectType?: TypeSource;
  workingAgent?: Member;
  /** «Por tomar» o «Por aprobar» cuando la etapa espera a alguien con su rol. */
  waiting?: string;
  plan?: CardPlan;
  dragging?: boolean;
}) {
  const blocked = (plan?.waitingFor?.length ?? 0) > 0;
  return (
    <div
      className={clsx(
        "rounded-md border bg-raised px-3 py-2.5 text-left transition-colors",
        dragging ? "rotate-[1.5deg] border-sol shadow-float" : "border-line-strong hover:border-line-hover",
      )}
    >
      <p className="text-[13px] leading-snug text-fg-strong">{issue.title}</p>
      {(epicTitle || issue.labels.length > 0 || plan?.role || plan?.progress || blocked) && (
        <div className="mt-2 flex flex-wrap gap-1">
          {epicTitle && <span className="max-w-full truncate rounded bg-epic/12 px-1.5 py-px text-[11px] font-medium text-epic">{epicTitle}</span>}
          {plan?.role && <span className="rounded bg-info/12 px-1.5 py-px text-[11px] font-medium text-info">{plan.role}</span>}
          {blocked && (
            <span title="No arranca hasta que terminen" className="rounded bg-warn/12 px-1.5 py-px text-[11px] font-medium text-warn">
              Espera a {plan!.waitingFor!.join(", ")}
            </span>
          )}
          {plan?.planPending && <span className="rounded bg-warn/12 px-1.5 py-px text-[11px] font-medium text-warn">Reparto por aprobar</span>}
          {plan?.progress && (
            <span title="Tareas terminadas" className="tabular rounded bg-hover px-1.5 py-px font-mono text-[11px] text-fg">
              {plan.progress.done}/{plan.progress.total} tareas
            </span>
          )}
          {issue.labels.map((label) => (
            <span key={label} className="rounded bg-hover px-1.5 py-px text-[11px] text-fg-muted">
              {label}
            </span>
          ))}
        </div>
      )}
      {workingAgent && <WorkingLine agent={workingAgent} className="mt-2" />}
      <div className="mt-2.5 flex items-center gap-2">
        <TypeIcon type={issue.type} projectType={projectType} />
        <span className="tabular font-mono text-[11px] text-fg-muted">{issue.key}</span>
        <span className="ml-auto flex items-center gap-1.5">
          {issue.reviewBy && (
            <span title={issue.reviewBy === "PERSON" ? "Sus puertas las revisa siempre una persona" : "Sus puertas las revisa el agente"} className="text-[11px]">
              {issue.reviewBy === "PERSON" ? "👤" : "🤖"}
            </span>
          )}
          <PriorityIcon priority={issue.priority} />
          {issue.storyPoints != null && (
            <span title="Story points" className="tabular min-w-5 rounded-full bg-hover px-1.5 text-center font-mono text-[11px] text-fg">
              {issue.storyPoints}
            </span>
          )}
          {waiting && !assignee ? (
            <span className="rounded border border-dashed border-line-hover px-1.5 py-px text-[10px] font-medium text-fg-muted">{waiting}</span>
          ) : (
            <Avatar member={assignee} size="xs" />
          )}
        </span>
      </div>
    </div>
  );
}
