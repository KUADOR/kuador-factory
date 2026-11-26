import type { Metadata } from "next";

import { canAdminister } from "@/lib/access";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { ManualSection } from "../methodology/ManualSection";
import { SettingsSection } from "../SettingsSection";
import { subjectsOf } from "@/lib/subjects";

import { BoardSubjects } from "./BoardSubjects";
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
      boardApi.subjects().catch(() => []),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [project, templates, issues, me, goal, allSubjects] = data;
  const template = templates.find((t) => t.key === project.templateKey);
  const facts = [
    { label: "Clave", value: <span className="font-mono">{project.key}</span>, hint: "No se cambia." },
    { label: "Plantilla de origen", value: template?.name ?? project.templateKey, hint: "El tablero es una copia: cambiar la plantilla no lo cambia." },
    { label: "Tarjetas", value: <span className="tabular font-mono">{issues.length}</span>, hint: `${project.columns.length} pasos` },
  ];
  return (
    <div className="flex flex-col gap-10">
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
    </SettingsSection>
    {subjectsOf(project).length > 0 && (
      <SettingsSection title="Proyectos" description="En qué trabaja este equipo. La épica elige uno y sus tarjetas lo heredan.">
        <BoardSubjects projectKey={project.key} subjects={subjectsOf(project)} all={allSubjects} />
      </SettingsSection>
    )}
    <div id="manual" className="scroll-mt-4">
      <ManualSection projectKey={project.key} />
    </div>
    {canAdminister(me) && (
    <SettingsSection title="Avanzado">
      <CapabilitiesForm project={project} />
      {/* Borrar un tablero es de quien administra el espacio. */}
      <DeleteProject projectKey={project.key} projectName={project.name} issues={issues.length} software={project.kind === "SOFTWARE"} />
    </SettingsSection>
    )}
    </div>
  );
}
