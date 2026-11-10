import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { PROJECT_KIND_LABEL } from "@/lib/labels";
import { failPage } from "@/lib/page-errors";

import { SettingsSection } from "../SettingsSection";
import { ProjectDetailsForm } from "./ProjectDetailsForm";

export const dynamic = "force-dynamic";

export async function generateMetadata({ params }: PageProps<"/projects/[key]/settings/general">): Promise<Metadata> {
  return { title: `Configuración ${(await params).key.toUpperCase()}` };
}

export default async function GeneralSettingsPage({ params }: PageProps<"/projects/[key]/settings/general">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.project(key), boardApi.catalog("workflow-templates"), boardApi.issues(key)]);
  } catch (e) {
    return failPage(e);
  }
  const [project, templates, issues] = data;
  const template = templates.find((t) => t.key === project.templateKey);
  const facts = [
    { label: "Clave", value: <span className="font-mono">{project.key}</span>, hint: `Prefijo de sus issues (${project.key}-1, ${project.key}-2…). No se cambia.` },
    { label: "Tipo", value: PROJECT_KIND_LABEL[project.kind], hint: project.kind === "SOFTWARE" ? "Con metodología, documentos y repositorio." : "Solo tablero." },
    { label: "Plantilla de origen", value: template?.name ?? project.templateKey, hint: "Sus columnas se copiaron al crear el proyecto; cambiar la plantilla no lo afecta." },
    { label: "Issues", value: <span className="tabular font-mono">{issues.length}</span>, hint: `${project.columns.length} columnas en el flujo.` },
  ];
  return (
    <SettingsSection title="General">
      <ProjectDetailsForm project={project} />
      <dl className="grid gap-px overflow-hidden rounded-lg border border-line bg-line sm:grid-cols-2">
        {facts.map((f) => (
          <div key={f.label} className="bg-surface px-5 py-3.5">
            <dt className="text-xs text-fg-muted">{f.label}</dt>
            <dd className="mt-0.5 text-[13px] font-medium text-fg-strong">{f.value}</dd>
            <dd className="mt-0.5 text-xs text-fg-faint">{f.hint}</dd>
          </div>
        ))}
      </dl>
    </SettingsSection>
  );
}
