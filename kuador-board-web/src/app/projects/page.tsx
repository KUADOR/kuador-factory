import type { Metadata } from "next";
import { Suspense } from "react";

import { ProjectsView } from "@/components/projects/ProjectsView";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Tableros" };

export default async function ProjectsPage() {
  let data;
  try {
    data = await Promise.all([
      boardApi.projects(),
      boardApi.catalog("workflow-templates"),
      boardApi.catalog("methodologies"),
      boardApi.workspacePackage().catch(() => undefined),
      boardApi.subjects().catch(() => []),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [projects, templates, methodologies, pkg, subjects] = data;
  return (
    <Suspense>
      <ProjectsView projects={projects} templates={templates} methodologies={methodologies} recommended={pkg?.templates ?? []} subjects={subjects} />
    </Suspense>
  );
}
