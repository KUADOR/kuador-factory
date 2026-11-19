import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { SettingsSection } from "../SettingsSection";
import { TeamEditor } from "./TeamEditor";

export const dynamic = "force-dynamic";

/** Herramientas que cambian el código del repo: el rol que las usa solo trabaja en la PC de una persona (como en el API). */
const CODE_TOOLS = ["Edit", "Write"];
export const metadata: Metadata = { title: "Equipo de agentes" };

export default async function TeamSettingsPage({ params }: PageProps<"/projects/[key]/settings/agents">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.boardTeam(key), boardApi.catalog("roles"), boardApi.workspaceAi(), boardApi.project(key)]);
  } catch (e) {
    return failPage(e);
  }
  const [team, roles, ai, project] = data;
  return (
    <SettingsSection title="Equipo de agentes">
      <TeamEditor
        projectKey={key}
        software={project.kind === "SOFTWARE"}
        team={team}
        roles={roles.map((r) => ({ key: r.key, name: r.name, description: r.description, worksOnCode: r.tools.some((t) => CODE_TOOLS.includes(t)) }))}
        ai={ai}
      />
    </SettingsSection>
  );
}
