import type { Metadata } from "next";

import { ProjectsList } from "@/components/org/ProjectsList";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Proyectos" };

export default async function OrgProjectsPage({ searchParams }: PageProps<"/org/projects">) {
  const params = await searchParams;
  let data;
  try {
    data = await Promise.all([boardApi.subjects(), boardApi.projects()]);
  } catch (e) {
    return failPage(e);
  }
  const [subjects, boards] = data;
  return <ProjectsList subjects={subjects} boards={boards.map((b) => ({ key: b.key, name: b.name }))} creating={params.nuevo === "1"} />;
}
