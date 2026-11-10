import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { SettingsSection } from "../SettingsSection";
import { WorkflowEditor } from "../WorkflowEditor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Columnas" };

export default async function ColumnsSettingsPage({ params }: PageProps<"/projects/[key]/settings/columnas">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.project(key), boardApi.issues(key)]);
  } catch (e) {
    return failPage(e);
  }
  const [project, issues] = data;
  const counts: Record<string, number> = {};
  issues.forEach((i) => (counts[i.status] = (counts[i.status] ?? 0) + 1));
  return (
    <SettingsSection
      title="Columnas"
      description="También puedes editarlas desde el tablero. La categoría dice si una columna es de inicio, de trabajo en curso o final."
    >
      <div className="rounded-lg border border-line bg-surface p-5">
        <WorkflowEditor project={project} counts={counts} />
      </div>
    </SettingsSection>
  );
}
