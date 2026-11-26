import { boardApi } from "@/lib/api";
import { rolesForBoard } from "@/lib/labels";
import { failPage } from "@/lib/page-errors";

import { SettingsSection } from "../SettingsSection";
import { TeamEditor } from "./TeamEditor";


/** Herramientas que cambian el código del repo: el rol que las usa solo trabaja en la PC de una persona (como en el API). */
const CODE_TOOLS = ["Edit", "Write"];

export async function AgentsSection({ projectKey: key }: { projectKey: string }) {
  let data;
  try {
    data = await Promise.all([boardApi.boardTeam(key), boardApi.catalog("roles"), boardApi.workspaceAi(), boardApi.project(key)]);
  } catch (e) {
    return failPage(e);
  }
  const [team, roles, ai, project] = data;
  return (
    <SettingsSection title="Agentes" description="Los agentes del espacio que trabajan en este tablero. Su modelo y dónde trabajan valen para todos los tableros.">
      <TeamEditor
        projectKey={key}
        software={project.kind === "SOFTWARE"}
        team={team}
        roles={rolesForBoard(roles, project.capabilities.includes("REPOSITORY")).map((r) => ({ key: r.key, name: r.name, description: r.description, worksOnCode: r.tools.some((t) => CODE_TOOLS.includes(t)) }))}
        ai={ai}
      />
    </SettingsSection>
  );
}
