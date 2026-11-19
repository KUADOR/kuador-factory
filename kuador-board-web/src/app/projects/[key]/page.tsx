import type { Metadata } from "next";

import { Board } from "@/components/board/Board";
import { SetupBanner } from "@/components/project/SetupBanner";
import { boardApi } from "@/lib/api";
import { canEdit } from "@/lib/access";
import { setupDone, setupSteps } from "@/lib/onboarding";
import { failPage } from "@/lib/page-errors";
import type { Project } from "@/lib/types";

export const dynamic = "force-dynamic";

type Props = { params: Promise<{ key: string }>; searchParams: Promise<{ issue?: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { key } = await params;
  return { title: `Tablero ${key.toUpperCase()}` };
}

export default async function BoardPage({ params, searchParams }: Props) {
  const { key } = await params;
  const { issue } = await searchParams;
  const projectKey = key.toUpperCase();
  let data;
  try {
    data = await Promise.all([
      boardApi.project(projectKey),
      boardApi.issues(projectKey),
      boardApi.members(),
      boardApi.agentRuns({ limit: 50 }).catch(() => []),
      boardApi.me(),
      boardApi.catalog("roles"),
      boardApi.boardTeam(projectKey).catch(() => null),
      boardApi.boardPeople(projectKey).catch(() => null),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [project, issues, everyone, runs, me, roles, team, people] = data;
  // En este tablero se asignan sus personas y su equipo de agentes; se conservan quienes ya tienen tarjetas aquí.
  const teamIds = new Set((team ?? []).filter((a) => a.inTeam).map((a) => a.id));
  const peopleIds = people == null ? null : new Set(people.filter((p) => p.inBoard).map((p) => p.id));
  const assigned = new Set(issues.map((i) => i.assigneeId).filter(Boolean));
  const members = everyone.filter((m) =>
    m.kind === "HUMAN" ? peopleIds == null || peopleIds.has(m.id) || assigned.has(m.id) : team == null || teamIds.has(m.id) || assigned.has(m.id),
  );
  const pending = canEdit(me) ? await pendingSetup(project, issues.length) : 0;
  return (
    <>
      <SetupBanner projectKey={project.key} pending={pending} />
      <Board
        project={project}
        issues={issues}
        members={members}
        runs={runs}
        me={me}
        roles={roles}
        initialOpenKey={issue?.toUpperCase()}
        modes={Object.fromEntries((team ?? []).map((a) => [a.id, a.mode]))}
        everyone={everyone}
      />
    </>
  );
}

/** Pasos del asistente que faltan (los mismos que mide su página). */
async function pendingSetup(project: Project, issues: number): Promise<number> {
  const [link, connections, notifications, ai, draft] = await Promise.all([
    project.kind === "SOFTWARE" ? boardApi.repository(project.key).catch(() => null) : null,
    boardApi.runnerTokens().catch(() => []),
    boardApi.notifications().catch(() => null),
    project.kind === "SOFTWARE" ? null : boardApi.workspaceAi().catch(() => null),
    project.kind === "SOFTWARE" ? boardApi.draftPlan(project.key).catch(() => null) : null,
  ]);
  const done = setupDone(project, link, connections, notifications, issues, !!ai?.configured, draft);
  return setupSteps(project).filter((s) => !done[s]).length;
}
