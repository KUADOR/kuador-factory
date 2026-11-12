import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { SettingsSection } from "../SettingsSection";
import { TeamEditor } from "./TeamEditor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Equipo de agentes" };

export default async function TeamSettingsPage({ params }: PageProps<"/projects/[key]/settings/equipo">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.boardTeam(key), boardApi.catalog("roles"), boardApi.workspaceAi(), boardApi.project(key)]);
  } catch (e) {
    return failPage(e);
  }
  const [team, roles, ai, project] = data;
  return (
    <SettingsSection
      title="Equipo de agentes"
      description="Quién trabaja en este tablero: en el tablero con la IA de la empresa, o en la PC de una persona."
    >
      <TeamEditor
        projectKey={key}
        software={project.kind === "SOFTWARE"}
        team={team}
        roles={roles.map((r) => ({ key: r.key, name: r.name, description: r.description }))}
        ai={ai}
      />
    </SettingsSection>
  );
}
