import type { Metadata } from "next";

import { Office } from "@/components/office/Office";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Oficina" };

/** La oficina en vivo: cada persona y agente del tablero en su puesto, y las tarjetas que van de uno a otro. */
export default async function OfficePage({ params }: { params: Promise<{ key: string }> }) {
  const projectKey = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([
      boardApi.project(projectKey),
      boardApi.issues(projectKey),
      boardApi.members(),
      boardApi.agentRuns({ limit: 100 }).catch(() => []),
      boardApi.me(),
      boardApi.catalog("roles"),
      boardApi.boardTeam(projectKey).catch(() => null),
      boardApi.boardPeople(projectKey).catch(() => null),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [project, issues, everyone, runs, me, roles, team, people] = data;
  // Los mismos puestos que el tablero: sus personas y su equipo de agentes, más quien tenga tarjetas aquí.
  const teamIds = new Set((team ?? []).filter((a) => a.inTeam).map((a) => a.id));
  const peopleIds = people == null ? null : new Set(people.filter((p) => p.inBoard).map((p) => p.id));
  const assigned = new Set(issues.map((i) => i.assigneeId).filter(Boolean));
  const members = everyone.filter((m) =>
    m.kind === "HUMAN" ? peopleIds == null || peopleIds.has(m.id) || assigned.has(m.id) : team == null || teamIds.has(m.id) || assigned.has(m.id),
  );
  const modes = Object.fromEntries((team ?? []).map((a) => [a.id, a.mode]));
  return (
    <Office
      project={project}
      issues={issues}
      members={members}
      runs={runs.filter((r) => r.projectKey === project.key)}
      me={me}
      roleNames={Object.fromEntries(roles.map((r) => [r.key, r.name]))}
      modes={modes}
      now={requestTime()}
    />
  );
}

/** Hora de esta petición: con ella se decide si un error o una pregunta del agente es reciente. */
function requestTime(): number {
  return Date.now();
}
