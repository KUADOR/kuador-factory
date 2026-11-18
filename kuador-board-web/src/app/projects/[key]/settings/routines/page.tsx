import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import type { TeamAgent } from "@/lib/types";

import { SettingsSection } from "../SettingsSection";
import { RoutinesEditor } from "./RoutinesEditor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Rutinas" };

export default async function RoutinesPage({ params }: PageProps<"/projects/[key]/settings/routines">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([
      boardApi.routines(key),
      boardApi.project(key),
      boardApi.boardTeam(key).catch(() => [] as TeamAgent[]),
      boardApi.boardPeople(key).catch(() => null),
      boardApi.members(),
      boardApi.agentKit("claude-code", []).catch(() => null),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [routines, project, team, people, members] = data;
  const kit = data[5];
  // Para quién: las personas del tablero y su equipo de agentes.
  const inBoard = people == null ? null : new Set(people.filter((p) => p.inBoard).map((p) => p.id));
  const teamIds = new Set(team.filter((a) => a.inTeam).map((a) => a.id));
  const assignees = members
    .filter((m) => (m.kind === "AGENT" ? teamIds.has(m.id) : inBoard == null || inBoard.has(m.id)))
    .map((m) => ({ id: m.id, name: m.name, agent: m.kind === "AGENT" }));
  const apiBase = kit ? kit.connection.url.replace(/\/mcp$/, "") : "";
  return (
    <SettingsSection
      title="Rutinas"
      description="Trabajo que se repite: a su hora el tablero crea la tarjeta y se la da a quien elijas. Un agente la trabaja solo; también se disparan desde afuera con su webhook."
    >
      <RoutinesEditor
        projectKey={key}
        routines={routines}
        columns={project.columns.filter((c) => c.category !== "DONE").map((c) => ({ key: c.key, name: c.name }))}
        assignees={assignees}
        apiBase={apiBase}
      />
    </SettingsSection>
  );
}
