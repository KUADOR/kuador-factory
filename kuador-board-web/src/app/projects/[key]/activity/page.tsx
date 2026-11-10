import type { Metadata } from "next";
import Link from "next/link";

import { ActivityItem } from "@/components/activity/ActivityItem";
import { EmptyState } from "@/components/ui/Section";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import type { Activity } from "@/lib/types";

export const dynamic = "force-dynamic";

export async function generateMetadata({ params }: PageProps<"/projects/[key]/activity">): Promise<Metadata> {
  return { title: `Actividad ${(await params).key.toUpperCase()}` };
}

const dayFormat = new Intl.DateTimeFormat("es-EC", { weekday: "long", day: "numeric", month: "long", timeZone: "America/Guayaquil" });
const dayKey = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Guayaquil" });

/** Agrupa por día (en hora de Ecuador) para leer la actividad como un diario. */
function byDay(activity: Activity[]): [string, Activity[]][] {
  return [...Map.groupBy(activity, (a) => dayKey.format(new Date(a.occurredAt))).entries()];
}

export default async function ProjectActivityPage({ params }: PageProps<"/projects/[key]/activity">) {
  const projectKey = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.project(projectKey), boardApi.projectActivity(projectKey, 100), boardApi.members(), boardApi.issues(projectKey)]);
  } catch (e) {
    return failPage(e);
  }
  const [project, activity, members, issues] = data;
  const byId = new Map(members.map((m) => [m.id, m]));
  const live = new Set(issues.map((i) => i.key));

  return (
    <div className="flex flex-col gap-6 px-6 py-6">
      <p className="text-[13px] text-fg-muted">Últimos 100 cambios de personas y agentes, del más reciente al más antiguo.</p>
      {activity.length === 0 ? (
        <EmptyState title="Todavía no hay actividad">Cada cambio de un issue o documento, de una persona o de un agente, queda registrado aquí.</EmptyState>
      ) : (
        byDay(activity).map(([day, entries]) => (
          <section key={day} className="flex flex-col gap-3">
            <h2 className="text-xs font-medium text-fg-muted first-letter:uppercase">{dayFormat.format(new Date(entries[0].occurredAt))}</h2>
            <ol className="flex flex-col gap-4 border-l border-line pl-4">
              {entries.map((a) => (
                <ActivityItem
                  key={a.id}
                  activity={a}
                  members={byId}
                  project={project}
                  issueLink={
                    !a.issueKey ? (
                      <span className="text-xs text-fg-muted">proyecto</span>
                    ) : live.has(a.issueKey) ? (
                      <Link href={`/projects/${project.key}?issue=${a.issueKey}`} className="k-link tabular font-mono text-xs">
                        {a.issueKey}
                      </Link>
                    ) : (
                      <span className="tabular font-mono text-xs text-fg-faint line-through">{a.issueKey}</span>
                    )
                  }
                />
              ))}
            </ol>
          </section>
        ))
      )}
    </div>
  );
}
