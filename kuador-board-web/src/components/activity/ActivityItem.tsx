import Link from "next/link";

import { Avatar } from "@/components/ui/Avatar";
import type { Activity, Member, Project } from "@/lib/types";

import { describe } from "./describe";

// Zona horaria fija: el servidor puede estar en UTC y el equipo trabaja en Ecuador.
const dateFormat = new Intl.DateTimeFormat("es-EC", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "America/Guayaquil",
});

export function ActivityItem({
  activity,
  members,
  project,
  issueLink,
}: {
  activity: Activity;
  members: Map<string, Member>;
  project: Project;
  issueLink?: React.ReactNode;
}) {
  const actor = activity.actorId ? members.get(activity.actorId) : undefined;
  const { text, quote, href } = describe(activity, members, project);
  return (
    <li className="flex gap-2.5">
      <Avatar member={actor} size="sm" />
      <div className="min-w-0 flex-1 text-[13px]">
        <p className="leading-snug text-fg">
          <span className="font-medium text-fg-strong">{actor?.name ?? "Sistema"}</span>{" "}
          {issueLink && <>{issueLink} · </>}
          {href ? (
            <Link href={href} className="underline decoration-line-hover underline-offset-2 hover:text-fg-strong">
              {text}
            </Link>
          ) : (
            text
          )}
        </p>
        {quote && (
          <p className="mt-1 border-l-2 border-line-strong pl-2 text-xs break-words whitespace-pre-wrap text-fg-muted">
            {quote}
          </p>
        )}
        <time dateTime={activity.occurredAt} className="tabular text-[11px] text-fg-faint">
          {dateFormat.format(new Date(activity.occurredAt))}
        </time>
      </div>
    </li>
  );
}
