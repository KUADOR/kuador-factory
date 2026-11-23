import { LiveUpdates } from "@/components/live/LiveUpdates";
import { BoardChat } from "@/components/project/BoardChat";
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
    <LiveUpdates projectKey={project.key}>
      {/* Alto fijo a la pantalla: el tablero ocupa lo que queda y sus columnas hacen scroll por dentro (como Jira); las
        demás páginas, más altas, hacen scroll con la página. */}
      <div className="flex h-full flex-col">
        <PageHeader
          crumbs={[{ label: "Tableros", href: "/projects" }, { label: project.key }]}
          title={project.name}
          description={project.description}
          mobileAside={
            <ProjectTabs
              projectKey={project.key}
              capabilities={project.capabilities}
              documents={project.methodology != null}
              templateKey={project.templateKey}
              variant="picker"
            />
          }
        >
          <ProjectTabs
            projectKey={project.key}
            capabilities={project.capabilities}
              documents={project.methodology != null}
            templateKey={project.templateKey}
          />
        </PageHeader>
        {children}
      </div>
      <BoardChat
        projectKey={project.key}
        firstColumn={project.columns[0]?.key ?? ""}
        epics={project.issueTypes.some((t) => t.type === "EPIC")}
      />
    </LiveUpdates>
  );
}
