import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import type { GitHubOverview } from "@/lib/types";

import { RepositoryForm } from "../RepositoryForm";
import { SettingsSection } from "../SettingsSection";
import { CreateRepository } from "./CreateRepository";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Repositorio" };

const NO_GITHUB: GitHubOverview = { app: null, canRegister: false, connections: [] };

export default async function RepositorySettingsPage({ params }: PageProps<"/projects/[key]/settings/repositorio">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.project(key), boardApi.repository(key), boardApi.github().catch(() => NO_GITHUB)]);
  } catch (e) {
    return failPage(e);
  }
  const [project, link, github] = data;
  if (project.kind !== "SOFTWARE") notFound();
  return (
    <div className="flex flex-col gap-8">
      {!link.linked && (
        <SettingsSection
          title="Crear repositorio"
          description="El tablero crea el repo en GitHub con sus bases: metodología, AGENTS.md, CI mínimo, develop como rama de trabajo y main protegida."
        >
          <div className="rounded-lg border border-line bg-surface p-5">
            <CreateRepository projectKey={project.key} projectName={project.name} connections={github.connections} />
          </div>
        </SettingsSection>
      )}
      <SettingsSection title={link.linked ? "Repositorio" : "Vincular uno existente"} description="El tablero lee la rama principal y propone sus cambios como Pull Requests.">
        <div className="rounded-lg border border-line bg-surface p-5">
          <RepositoryForm projectKey={project.key} link={link} connections={github.connections} />
        </div>
      </SettingsSection>
    </div>
  );
}
