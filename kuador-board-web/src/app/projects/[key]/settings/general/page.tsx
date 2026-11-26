import type { Metadata } from "next";

import { canAdminister } from "@/lib/access";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import Link from "next/link";

import { SettingsSection } from "../SettingsSection";
import { subjectsOf } from "@/lib/subjects";

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
  const subjects = subjectsOf(project);
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
    {/* Quién lo atiende y en qué trabaja: se arma en Organización; aquí solo se nombra. */}
    <p className="-mt-4 text-[13px] text-fg-muted">
      {project.team ? <>Lo atiende <span className="font-medium text-fg-strong">{project.team.name}</span></> : "Sin equipo"}
      {subjects.length > 1 && <> · Trabaja en {subjects.map((s) => s.name).join(", ")}</>}
      {canAdminister(me) && (
        <>
          {" · "}
          <Link href={`/org/boards?tablero=${project.key}`} className="k-link">
            Cambiar
          </Link>
        </>
      )}
    </p>
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
