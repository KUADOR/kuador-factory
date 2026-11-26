import { boardApi } from "@/lib/api";
import { can } from "@/lib/labels";
import { failPage } from "@/lib/page-errors";

import { MethodologyPicker } from "../MethodologyPicker";
import { SettingsSection } from "../SettingsSection";


export async function ManualSection({ projectKey: key }: { projectKey: string }) {
  let data;
  try {
    data = await Promise.all([boardApi.project(key), boardApi.catalog("methodologies")]);
  } catch (e) {
    return failPage(e);
  }
  const [project, methodologies] = data;
  if (!can(project, "DOCUMENTS")) return null;
  return (
    <SettingsSection title="Manual de trabajo" description="Qué documentos lleva el tablero y cómo trabajan los agentes. Cambiarlo no borra documentos.">
      <div className="rounded-lg border border-line bg-surface p-5">
        <MethodologyPicker project={project} methodologies={methodologies} />
      </div>
    </SettingsSection>
  );
}
