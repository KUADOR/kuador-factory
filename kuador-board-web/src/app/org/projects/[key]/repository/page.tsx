import type { Metadata } from "next";

import { AgentKitPanel } from "@/app/projects/[key]/sync/AgentKitPanel";
import { SyncPanel } from "@/app/projects/[key]/sync/SyncPanel";
import { RepositoryForm } from "@/app/projects/[key]/settings/RepositoryForm";
import { PiecesList, PiecesSetupForm } from "@/app/projects/[key]/settings/repository/Pieces";
import { ApiError, boardApi, projectScope } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { subjectHosts } from "@/lib/subject-hosts";
import type { GitHubOverview, RepoKit } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Repositorio del proyecto" };

const NO_GITHUB: GitHubOverview = { app: null, canRegister: false, connections: [] };

/** El repositorio del proyecto, sus piezas y su sincronización: se arman una vez y los usan todos sus tableros de código. */
export default async function OrgProjectRepositoryPage({ params }: PageProps<"/org/projects/[key]/repository">) {
  const key = (await params).key.toUpperCase();
  const scope = projectScope(key);
  let data;
  try {
    data = await Promise.all([
      boardApi.subject(key),
      boardApi.projects(),
      boardApi.repository(scope),
      boardApi.github().catch(() => NO_GITHUB),
      boardApi.catalog("component-templates"),
      boardApi.pieces(scope),
      boardApi.piecePlan(scope).catch(() => null),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [subject, boards, link, github, templates, pieces, plan] = data;
  const hosts = subjectHosts(subject, boards);
  if (hosts.code.length === 0) {
    return <p className="px-4 py-5 text-[13px] text-fg-faint sm:px-6">Ningún tablero de código trabaja este proyecto todavía.</p>;
  }
  const viaApp = link.linked && link.kind === "GITHUB_APP";
  let sync = null;
  if (link.linked && subject.methodology) {
    try {
      const [status, ...issues] = await Promise.all([boardApi.syncStatus(scope), ...hosts.code.map((b) => boardApi.issues(b.key))]);
      sync = { status, issues: issues.flat().filter((i) => i.subjectKey === key) };
    } catch (e) {
      return failPage(e);
    }
  }
  let kit: { repos: RepoKit[]; error: string | null } | null = null;
  if (viaApp) {
    kit = await boardApi.agentKitStatus(scope).then(
      (repos) => ({ repos, error: null }),
      (e: unknown) => ({ repos: [], error: e instanceof ApiError ? e.message : "No se pudo leer el kit de agentes del repo." }),
    );
  }
  return (
    <div className="flex max-w-5xl flex-col gap-10 px-4 py-5 sm:px-6 sm:py-6">
      {!link.linked ? (
        <section className="flex flex-col gap-3">
          <h2 className="text-sm font-semibold text-fg-strong">Armar el proyecto en GitHub</h2>
          <PiecesSetupForm projectKey={scope} projectName={subject.name} connections={github.connections} templates={templates} plan={plan} />
        </section>
      ) : (
        viaApp && (
          <section className="flex flex-col gap-3">
            <h2 className="text-sm font-semibold text-fg-strong">Piezas</h2>
            <PiecesList projectKey={scope} pieces={pieces} templates={templates} mainRepo={link.location} />
          </section>
        )
      )}
      <section className="flex flex-col gap-3">
        <h2 className="text-sm font-semibold text-fg-strong">{link.linked ? "Repositorio" : "O vincular uno existente"}</h2>
        <RepositoryForm projectKey={scope} link={link} connections={github.connections} syncHref={null} />
      </section>
      {sync && (
        <section className="flex flex-col gap-3">
          <h2 className="text-sm font-semibold text-fg-strong">Sincronizar documentos</h2>
          <SyncPanel projectKey={scope} status={sync.status} issues={sync.issues} />
        </section>
      )}
      {kit && <AgentKitPanel projectKey={scope} repos={kit.repos} error={kit.error} />}
    </div>
  );
}
