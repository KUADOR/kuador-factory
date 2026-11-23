import type { Metadata } from "next";

import { canAdminister } from "@/lib/access";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { SettingsSection } from "../SettingsSection";
import { CapabilitiesForm } from "./CapabilitiesForm";
import { DeleteProject } from "./DeleteProject";
import { GoalForm } from "./GoalForm";
import { ProjectDetailsForm } from "./ProjectDetailsForm";

export const dynamic = "force-dynamic";

export async function generateMetadata({ params }: PageProps<"/projects/[key]/settings/general">): Promise<Metadata> {
  return { title: `Configuración ${(await params).key.toUpperCase()}` };
}

export default async function GeneralSettingsPage({ params }: PageProps<"/projects/[key]/settings/general">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([
      boardApi.project(key),
      boardApi.catalog("workflow-templates"),
      boardApi.issues(key),
      boardApi.me(),
      boardApi.projectGoal(key).catch(() => ({ goal: null })),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [project, templates, issues, me, goal] = data;
  const template = templates.find((t) => t.key === project.templateKey);
  const facts = [
    { label: "Clave", value: <span className="font-mono">{project.key}</span>, hint: "No se cambia." },
    { label: "Plantilla de origen", value: template?.name ?? project.templateKey, hint: "" },
    { label: "Issues", value: <span className="tabular font-mono">{issues.length}</span>, hint: `${project.columns.length} columnas` },
  ];
  return (
    <SettingsSection title="General">
      <GoalForm projectKey={project.key} initial={goal.goal} />
      <ProjectDetailsForm project={project} />
      <dl className="grid gap-px overflow-hidden rounded-lg border border-line bg-line sm:grid-cols-2">
        {facts.map((f) => (
          <div key={f.label} className="bg-surface px-5 py-3.5">
            <dt className="text-xs text-fg-muted">{f.label}</dt>
            <dd className="mt-0.5 text-[13px] font-medium text-fg-strong">{f.value}</dd>
            {f.hint && <dd className="mt-0.5 text-xs text-fg-faint">{f.hint}</dd>}
          </div>
        ))}
      </dl>
      {canAdminister(me) && <CapabilitiesForm project={project} />}
      {/* Borrar un tablero es de quien administra el espacio. */}
      {canAdminister(me) && <DeleteProject projectKey={project.key} projectName={project.name} issues={issues.length} software={project.kind === "SOFTWARE"} />}
    </SettingsSection>
  );
}
