import type { Metadata } from "next";

import { PageBody, PageHeader } from "@/components/shell/PageHeader";
import { OrgChart, type OrgAgent } from "@/components/team/OrgChart";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import type { TeamAgent } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Organigrama" };

const MONTH = 30 * 24 * 60 * 60 * 1000;

/** Quién hace qué en el espacio y cómo fluye el trabajo en cada tablero. */
export default async function OrgPage() {
  let data;
  try {
    data = await Promise.all([
      boardApi.members(),
      boardApi.runnerMachines().catch(() => []),
      boardApi.projects(),
      boardApi.catalog("roles"),
      boardApi.agentRuns({ limit: 300 }).catch(() => []),
      boardApi.workspace().catch(() => null),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [members, machines, projects, roles, runs, workspace] = data;
  // Dónde trabaja cada agente (servidor o PC de una persona): lo dice el equipo de cualquiera de sus tableros.
  const teams = await Promise.all(projects.map((p) => boardApi.boardTeam(p.key).catch(() => [] as TeamAgent[])));
  const mode = new Map(teams.flat().map((a) => [a.id, a.mode]));
  const roleNames = Object.fromEntries(roles.map((r) => [r.key, r.name]));
  // eslint-disable-next-line react-hooks/purity -- página dinámica: «hace 30 días» se mide al pedirla
  const since = Date.now() - MONTH;
  const agents: OrgAgent[] = members
    .filter((m) => m.kind === "AGENT")
    .map((m) => ({
      id: m.id,
      name: m.name,
      role: m.role,
      roleName: roleNames[m.role] ?? m.role,
      avatarColor: m.avatarColor,
      mode: mode.get(m.id) === "SERVER" ? "SERVER" : "LOCAL",
      delivered: runs.filter((r) => r.agentId === m.id && r.status === "SUCCEEDED" && new Date(r.createdAt).getTime() > since).length,
    }));
  return (
    <>
      <PageHeader title="Organigrama" description="Quién hace qué: las personas, sus máquinas y los agentes que atienden, y quién cubre cada etapa de cada tablero." />
      <PageBody>
        <OrgChart
          people={members.filter((m) => m.kind === "HUMAN")}
          agents={agents}
          machines={machines}
          projects={projects}
          roleNames={roleNames}
          workspaceName={workspace?.name ?? "Tu espacio"}
        />
      </PageBody>
    </>
  );
}
