import { Suspense } from "react";

import { ProjectsView } from "@/components/projects/ProjectsView";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";

export default async function ProjectsPage() {
  let data;
  try {
    data = await Promise.all([boardApi.projects(), boardApi.catalog("workflow-templates"), boardApi.catalog("methodologies")]);
  } catch (e) {
    return failPage(e);
  }
  const [projects, templates, methodologies] = data;
  return (
    <Suspense>
      <ProjectsView projects={projects} templates={templates} methodologies={methodologies} />
    </Suspense>
  );
}
