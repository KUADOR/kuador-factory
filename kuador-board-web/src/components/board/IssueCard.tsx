import { clsx } from "clsx";

import { WorkingLine } from "@/components/agents/WorkingLine";
import { Avatar } from "@/components/ui/Avatar";
import { PriorityIcon, TypeIcon } from "@/components/ui/IssueIcons";
import type { TypeSource } from "@/lib/labels";
import type { Issue, Member } from "@/lib/types";

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
  dragging = false,
}: {
  issue: Issue;
  assignee: Member | undefined;
  epicTitle: string | undefined;
  projectType?: TypeSource;
  workingAgent?: Member;
  /** «Por tomar» o «Por aprobar» cuando la etapa espera a alguien con su rol. */
  waiting?: string;
  dragging?: boolean;
}) {
  return (
    <div
      className={clsx(
        "rounded-md border bg-raised px-3 py-2.5 text-left transition-colors",
        dragging ? "rotate-[1.5deg] border-sol shadow-float" : "border-line-strong hover:border-line-hover",
      )}
    >
      <p className="text-[13px] leading-snug text-fg-strong">{issue.title}</p>
      {(epicTitle || issue.labels.length > 0) && (
        <div className="mt-2 flex flex-wrap gap-1">
          {epicTitle && <span className="max-w-full truncate rounded bg-epic/12 px-1.5 py-px text-[11px] font-medium text-epic">{epicTitle}</span>}
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
