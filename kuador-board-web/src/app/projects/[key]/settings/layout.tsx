import { boardApi } from "@/lib/api";
import { requireEditor } from "@/lib/require-editor";
import { failPage } from "@/lib/page-errors";

import { SettingsNav } from "./SettingsNav";

/** Configuración del proyecto: las secciones como pestañas arriba y una sección a la vez. */
export default async function ProjectSettingsLayout({ params, children }: LayoutProps<"/projects/[key]/settings">) {
  const projectKey = (await params).key.toUpperCase();
  await requireEditor(`/projects/${projectKey}`);
  let project;
  try {
    project = await boardApi.project(projectKey);
  } catch (e) {
    return failPage(e);
  }
  return (
    <div className="flex flex-col gap-4 px-4 py-4 sm:gap-6 sm:px-6 sm:py-5">
      <SettingsNav projectKey={project.key} kind={project.kind} />
      <div className="min-w-0">{children}</div>
    </div>
  );
}
