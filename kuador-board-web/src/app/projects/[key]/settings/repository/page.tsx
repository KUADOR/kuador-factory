import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import type { GitHubOverview } from "@/lib/types";

import { RepositoryForm } from "../RepositoryForm";
import { SettingsSection } from "../SettingsSection";
import { PiecesList, PiecesSetupForm } from "./Pieces";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Piezas y repositorio" };

const NO_GITHUB: GitHubOverview = { app: null, canRegister: false, connections: [] };

export default async function RepositorySettingsPage({ params }: PageProps<"/projects/[key]/settings/repository">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([
      boardApi.project(key),
      boardApi.repository(key),
      boardApi.github().catch(() => NO_GITHUB),
      boardApi.catalog("component-templates"),
      boardApi.pieces(key),
      boardApi.piecePlan(key).catch(() => null),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [project, link, github, templates, pieces, plan] = data;
  if (project.kind !== "SOFTWARE") notFound();
  const viaApp = link.linked && link.kind === "GITHUB_APP";
  return (
    <div className="flex flex-col gap-8">
      {!link.linked ? (
        <SettingsSection
          title="Armar el proyecto en GitHub"
          description="Las piezas del sistema (API, web, microfrontends…) y sus repositorios."
        >
          <div className="rounded-lg border border-line bg-surface p-5">
            <PiecesSetupForm projectKey={project.key} projectName={project.name} connections={github.connections} templates={templates} plan={plan} />
          </div>
        </SettingsSection>
      ) : (
        viaApp && (
          <SettingsSection title="Piezas">
            <div className="rounded-lg border border-line bg-surface p-5">
              <PiecesList projectKey={project.key} pieces={pieces} templates={templates} mainRepo={link.location} />
            </div>
          </SettingsSection>
        )
      )}
      <SettingsSection title={link.linked ? "Repositorio del proyecto" : "O vincular uno existente"} description="Los cambios se proponen como Pull Requests.">
        <div className="rounded-lg border border-line bg-surface p-5">
          <RepositoryForm projectKey={project.key} link={link} connections={github.connections} />
        </div>
      </SettingsSection>
    </div>
  );
}
