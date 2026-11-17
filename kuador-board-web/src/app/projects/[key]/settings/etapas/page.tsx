import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { roleLabel, stageRoleOptions } from "@/lib/labels";
import { failPage } from "@/lib/page-errors";

import { SettingsSection } from "../SettingsSection";
import { StagesEditor } from "./StagesEditor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Etapas y responsables" };

export default async function StagesSettingsPage({ params }: PageProps<"/projects/[key]/settings/etapas">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.project(key), boardApi.catalog("roles"), boardApi.members()]);
  } catch (e) {
    return failPage(e);
  }
  const [project, roles, members] = data;
  const humanRoles = [...new Set(["OWNER", ...members.filter((m) => m.kind === "HUMAN").map((m) => m.role)])];
  return (
    <SettingsSection title="Etapas y responsables" description="Quién toma el trabajo en cada columna y quién cierra cada puerta: una persona, o el agente revisor, que deja a una persona lo dudoso.">
      <StagesEditor
        project={project}
        roleOptions={stageRoleOptions(roles, humanRoles)}
        humanRoleOptions={humanRoles.map((k) => ({ value: k, label: roleLabel(k) }))}
      />
    </SettingsSection>
  );
}
