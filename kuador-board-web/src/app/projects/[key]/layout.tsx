import { ProjectTabs } from "@/components/project/ProjectTabs";
import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

/** Cabecera común de todas las páginas del proyecto: migas, nombre y pestañas. */
export default async function ProjectLayout({ params, children }: LayoutProps<"/projects/[key]">) {
  const projectKey = (await params).key.toUpperCase();
  let project;
  try {
    project = await boardApi.project(projectKey);
  } catch (e) {
    return failPage(e);
  }
  return (
    <div className="flex min-h-full flex-col">
      <PageHeader crumbs={[{ label: "Proyectos", href: "/" }, { label: project.key }]} title={project.name} description={project.description}>
        <ProjectTabs projectKey={project.key} kind={project.kind} />
      </PageHeader>
      {children}
    </div>
  );
}
