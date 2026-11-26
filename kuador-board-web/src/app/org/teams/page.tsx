import type { Metadata } from "next";

import { TeamsView } from "@/components/org/TeamsView";
import { agentRoles } from "@/lib/agent-roles";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Equipos" };

export default async function TeamsPage({ searchParams }: PageProps<"/org/teams">) {
  const params = await searchParams;
  let data;
  try {
    data = await Promise.all([boardApi.teams(), boardApi.members(), boardApi.catalog("roles"), boardApi.workspaceAi(), boardApi.projects()]);
  } catch (e) {
    return failPage(e);
  }
  const [teams, members, roles, ai, projects] = data;
  const open = typeof params.equipo === "string" ? params.equipo : null;
  return (
    <TeamsView
      teams={teams}
      people={members.filter((m) => m.kind === "HUMAN")}
      roles={agentRoles(roles)}
      ai={ai}
      boards={projects.map((p) => ({ key: p.key, name: p.name }))}
      initialOpen={open}
      creating={params.nuevo === "1"}
    />
  );
}
