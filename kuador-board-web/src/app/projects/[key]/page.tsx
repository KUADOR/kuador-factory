import type { Metadata } from "next";

import { Board } from "@/components/board/Board";
import { IssueList } from "@/components/board/IssueList";
import { SetupBanner } from "@/components/project/SetupBanner";
import { ApiError, boardApi } from "@/lib/api";
import { canEdit } from "@/lib/access";
import { setupDone, setupSteps } from "@/lib/onboarding";
import { failPage } from "@/lib/page-errors";
import { chosenIn } from "@/lib/subject-server";
import { subjectsOf } from "@/lib/subjects";
import type { BoardView, IssueListQuery, IssueSort, Project } from "@/lib/types";

export const dynamic = "force-dynamic";

type Search = { issue?: string; view?: string; q?: string; status?: string; assigneeId?: string; type?: string; label?: string; sort?: string; dir?: string; page?: string };
type Props = { params: Promise<{ key: string }>; searchParams: Promise<Search> };

const SORTS: IssueSort[] = ["key", "title", "type", "status", "priority", "updated"];

/** El tablero sin el historial; con una API anterior a esta web, el listado completo de siempre. */
async function loadBoard(projectKey: string): Promise<BoardView> {
  try {
    return await boardApi.board(projectKey);
  } catch (e) {
    if (!(e instanceof ApiError) || e.status !== 404) throw e;
    return { issues: await boardApi.issues(projectKey), totals: {}, progress: {}, parents: {}, doneVisible: 0 };
  }
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { key } = await params;
  return { title: `Tablero ${key.toUpperCase()}` };
}

export default async function BoardPage({ params, searchParams }: Props) {
  const { key } = await params;
  const search = await searchParams;
  const { issue } = search;
  const projectKey = key.toUpperCase();
  const listing = search.view === "list";
  const query: IssueListQuery = {
    q: search.q?.trim() || undefined,
    status: search.status || undefined,
    assigneeId: search.assigneeId || undefined,
    type: search.type || undefined,
    label: search.label?.trim() || undefined,
    sort: SORTS.includes(search.sort as IssueSort) ? (search.sort as IssueSort) : "updated",
    dir: search.dir === "asc" ? "asc" : "desc",
    page: Math.max(0, Number.parseInt(search.page ?? "0", 10) || 0),
  };
  let data;
  try {
    data = await Promise.all([
      boardApi.project(projectKey),
      loadBoard(projectKey),
      boardApi.members(),
      boardApi.agentRuns({ limit: 50 }).catch(() => []),
      boardApi.me(),
      boardApi.catalog("roles"),
      boardApi.boardTeam(projectKey).catch(() => null),
      boardApi.boardPeople(projectKey).catch(() => null),
      listing ? boardApi.searchIssues(projectKey, { ...query, size: 50 }) : null,
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [project, board, everyone, runs, me, roles, team, people, page] = data;
  // Una tarjeta enlazada que el tablero no trajo (terminada hace tiempo) se pide aparte, para poder abrirla.
  const wanted = issue?.toUpperCase();
  const linked = wanted && !board.issues.some((i) => i.key === wanted) ? await boardApi.issue(wanted).catch(() => null) : null;
  // En un tablero con varios proyectos, el elegido filtra las tarjetas (la enlazada se ve aunque sea de otro).
  const subject = await chosenIn(project);
  const all = linked && linked.projectKey === project.key ? [...board.issues, linked] : board.issues;
  const issues = subject ? all.filter((i) => i.subjectKey === subject.key || i.key === wanted) : all;
  // En este tablero se asignan sus personas y su equipo de agentes; se conservan quienes ya tienen tarjetas aquí.
  const teamIds = new Set((team ?? []).filter((a) => a.inTeam).map((a) => a.id));
  const peopleIds = people == null ? null : new Set(people.filter((p) => p.inBoard).map((p) => p.id));
  const assigned = new Set(issues.map((i) => i.assigneeId).filter(Boolean));
  const members = everyone.filter((m) =>
    m.kind === "HUMAN" ? peopleIds == null || peopleIds.has(m.id) || assigned.has(m.id) : team == null || teamIds.has(m.id) || assigned.has(m.id),
  );
  const pending = canEdit(me) ? await pendingSetup(project, issues.length) : 0;
  const modes = Object.fromEntries((team ?? []).map((a) => [a.id, a.mode]));
  if (page) {
    return (
      <IssueList
        project={project}
        page={page}
        query={query}
        members={members}
        everyone={everyone}
        epics={issues.filter((i) => i.type === "EPIC")}
        runs={runs}
        me={me}
        roles={roles}
        modes={modes}
      />
    );
  }
  return (
    <>
      <SetupBanner projectKey={project.key} pending={pending} />
      <Board
        // Con un proyecto elegido, cada columna cuenta lo que se ve (los totales del tablero son de todos).
        history={subject ? { ...board, totals: {} } : board}
        newCardSubject={subject?.key ?? subjectsOf(project)[0]?.key}
        project={project}
        issues={issues}
        members={members}
        runs={runs}
        me={me}
        roles={roles}
        initialOpenKey={issue?.toUpperCase()}
        modes={modes}
        everyone={everyone}
      />
    </>
  );
}

/** Pasos del asistente que faltan (los mismos que mide su página). */
async function pendingSetup(project: Project, issues: number): Promise<number> {
  const [link, connections, notifications, ai, draft, plan] = await Promise.all([
    project.kind === "SOFTWARE" ? boardApi.repository(project.key).catch(() => null) : null,
    boardApi.runnerTokens().catch(() => []),
    boardApi.notifications().catch(() => null),
    project.kind === "SOFTWARE" ? null : boardApi.workspaceAi().catch(() => null),
    project.kind === "SOFTWARE" ? boardApi.draftPlan(project.key).catch(() => null) : null,
    project.kind === "SOFTWARE" ? boardApi.piecePlan(project.key).catch(() => null) : null,
  ]);
  const done = setupDone(project, link, connections, notifications, issues, !!ai?.configured, draft, plan);
  return setupSteps(project).filter((s) => !done[s]).length;
}
