import type { Metadata } from "next";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/Section";

import { ApiError, boardApi } from "@/lib/api";
import type { RepoKit } from "@/lib/types";
import { requireAdmin } from "@/lib/require-editor";
import { failPage } from "@/lib/page-errors";

import { AgentKitPanel } from "./AgentKitPanel";
import { SyncPanel } from "./SyncPanel";

export const dynamic = "force-dynamic";

type Props = { params: Promise<{ key: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  return { title: `Repositorio ${(await params).key.toUpperCase()}` };
}

export default async function SyncPage({ params }: Props) {
  const projectKey = (await params).key.toUpperCase();
  await requireAdmin(`/projects/${projectKey}`);
  let project, link;
  try {
    [project, link] = await Promise.all([boardApi.project(projectKey), boardApi.repository(projectKey)]);
  } catch (e) {
    return failPage(e);
  }
  const where = link.linked ? `github.com/${link.location} · rama ${link.branch}` : null;
  const notReady = !link.linked ? "Vincula primero el repositorio" : !project.methodology ? "Elige primero el manual de trabajo" : null;
  if (notReady) {
    return (
      <div className="max-w-3xl px-4 py-5 sm:px-6 sm:py-6">
        <EmptyState title={notReady} action={<Button href={`/projects/${project.key}/settings/${link.linked ? "methodology" : "repository"}`} tone="primary" size="sm">{link.linked ? "Elegir manual de trabajo" : "Vincular repositorio"}</Button>} />
      </div>
    );
  }
  let data;
  try {
    data = await Promise.all([boardApi.syncStatus(projectKey), boardApi.issues(projectKey)]);
  } catch (e) {
    return failPage(e);
  }
  const [status, issues] = data;
  // El kit vive en los repos creados con la app de GitHub; si GitHub no responde, el resto de la página sigue.
  let kit: { repos: RepoKit[]; error: string | null } | null = null;
  if (link.kind === "GITHUB_APP") {
    kit = await boardApi.agentKitStatus(projectKey).then(
      (repos) => ({ repos, error: null }),
      (e: unknown) => ({ repos: [], error: e instanceof ApiError ? e.message : "No se pudo leer el kit de agentes del repo." }),
    );
  }
  return (
    <div className="flex flex-col gap-5 px-4 py-5 sm:px-6 sm:py-6">
      <p className="text-[13px] text-fg-muted">
        Vinculado a <code className="font-mono text-xs break-all text-fg">{where}</code>.
      </p>
      <SyncPanel projectKey={project.key} status={status} issues={issues} />
      {kit && <AgentKitPanel projectKey={project.key} repos={kit.repos} error={kit.error} />}
    </div>
  );
}
