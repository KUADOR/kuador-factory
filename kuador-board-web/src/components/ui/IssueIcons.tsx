import { Bookmark, Bug, ChevronDown, ChevronUp, ChevronsDown, ChevronsUp, Equal, Layers, SquareCheckBig } from "lucide-react";

import { PRIORITY_LABEL, typeLabel, type TypeSource } from "@/lib/labels";
import type { IssueType, Priority } from "@/lib/types";

const TYPE_STYLE: Record<IssueType, { Icon: typeof Bug; className: string }> = {
  EPIC: { Icon: Layers, className: "bg-epic/15 text-epic" },
  STORY: { Icon: Bookmark, className: "bg-ok/15 text-ok" },
  TASK: { Icon: SquareCheckBig, className: "bg-info/15 text-info" },
  BUG: { Icon: Bug, className: "bg-danger/15 text-danger" },
};

export function TypeIcon({ type, projectType }: { type: IssueType; projectType?: TypeSource }) {
  const { Icon, className } = TYPE_STYLE[type];
  const label = typeLabel(type, projectType);
  return (
    <span title={label} className={`inline-grid size-[18px] shrink-0 place-items-center rounded ${className}`}>
      <Icon className="size-3" strokeWidth={2.25} aria-label={label} />
    </span>
  );
}

const PRIORITY_STYLE: Record<Priority, { Icon: typeof Bug; className: string }> = {
  HIGHEST: { Icon: ChevronsUp, className: "text-danger" },
  HIGH: { Icon: ChevronUp, className: "text-warn" },
  MEDIUM: { Icon: Equal, className: "text-fg-muted" },
  LOW: { Icon: ChevronDown, className: "text-info" },
  LOWEST: { Icon: ChevronsDown, className: "text-fg-faint" },
};

export function PriorityIcon({ priority }: { priority: Priority }) {
  const { Icon, className } = PRIORITY_STYLE[priority];
  return (
    <Icon
      className={`size-4 shrink-0 ${className}`}
      strokeWidth={2.5}
      aria-label={`Prioridad ${PRIORITY_LABEL[priority]}`}
    />
  );
}
