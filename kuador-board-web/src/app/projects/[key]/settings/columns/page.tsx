import { AdminOnly } from "../AdminOnly";
import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { roleLabel, rolesForBoard, stageRoleOptions } from "@/lib/labels";
import { failPage } from "@/lib/page-errors";

import { SettingsSection } from "../SettingsSection";
import { StagesEditor } from "../stages/StagesEditor";
import { WorkflowEditor } from "../WorkflowEditor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Pasos y responsables" };

/** El flujo del tablero en una sola pantalla: los pasos (columnas) y, debajo, quién hace cada uno y quién aprueba. */
export default async function FlowSettingsPage({ params }: PageProps<"/projects/[key]/settings/columns">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([
      boardApi.project(key),
      boardApi.issues(key),
      boardApi.catalog("roles"),
      boardApi.members(),
      boardApi.catalog("methodologies").catch(() => []),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [project, issues, roles, members, methodologies] = data;
  const counts: Record<string, number> = {};
  issues.forEach((i) => (counts[i.status] = (counts[i.status] ?? 0) + 1));
  // Los documentos por tarjeta del manual del tablero: un paso puede exigirlos para avanzar.
  const documents = (methodologies.find((m) => m.key === project.methodology)?.documents ?? []).filter((d) => d.scope === "ISSUE");
  const humanRoles = [...new Set(["OWNER", ...members.filter((m) => m.kind === "HUMAN").map((m) => m.role)])];
  return (
    <>
      <SettingsSection title="Pasos" description="Las columnas del tablero, en el orden en que avanza el trabajo.">
        <div className="rounded-lg border border-line bg-surface p-5">
          <AdminOnly>
            <WorkflowEditor project={project} counts={counts} />
          </AdminOnly>
        </div>
      </SettingsSection>
      <div id="responsables" className="scroll-mt-4">
        <SettingsSection title="Responsables y aprobaciones" description="Quién toma el trabajo en cada paso y dónde tiene que aprobar una persona.">
          <AdminOnly>
            <StagesEditor
              project={project}
              roleOptions={stageRoleOptions(rolesForBoard(roles, project.capabilities.includes("REPOSITORY")), humanRoles)}
              humanRoleOptions={humanRoles.map((k) => ({ value: k, label: roleLabel(k) }))}
              documentOptions={documents.map((d) => ({ value: d.kind, label: d.label }))}
            />
          </AdminOnly>
        </SettingsSection>
      </div>
    </>
  );
}
