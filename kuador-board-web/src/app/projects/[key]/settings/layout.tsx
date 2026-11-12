import { boardApi } from "@/lib/api";
import { requireEditor } from "@/lib/require-editor";
import { failPage } from "@/lib/page-errors";

import { SettingsNav } from "./SettingsNav";

/** Configuración del proyecto: menú de secciones a la izquierda y una sección a la vez. */
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
    <div className="grid grid-cols-[minmax(0,1fr)] gap-6 px-6 py-6 md:grid-cols-[13rem_minmax(0,1fr)] md:gap-8">
      <aside className="md:sticky md:top-6 md:self-start">
        <SettingsNav projectKey={project.key} kind={project.kind} />
      </aside>
      <div className="min-w-0">{children}</div>
    </div>
  );
}
