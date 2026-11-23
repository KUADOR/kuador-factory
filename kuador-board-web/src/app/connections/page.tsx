import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireAdmin } from "@/lib/require-editor";

import { AiConnection } from "./AiConnection";
import { BusinessConnections } from "./BusinessConnections";
import { GitHubConnections } from "./GitHubConnections";
import { TelegramConnection } from "./TelegramConnection";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Conexiones" };

export default async function ConnectionsPage({ searchParams }: PageProps<"/connections">) {
  await requireAdmin();
  const params = await searchParams;
  let data;
  try {
    data = await Promise.all([
      boardApi.github(),
      boardApi.workspace(),
      boardApi.telegramSetup(),
      boardApi.workspaceAi(),
      boardApi.aiUsage().catch(() => null),
      boardApi.connections().catch(() => []),
      boardApi.projects().catch(() => []),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [github, workspace, telegram, ai, usage, connections, projects] = data;
  const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? null;
  return (
    <>
      <PageHeader title="Conexiones" width="narrow" />
      <GitHubConnections overview={github} workspaceName={workspace.name} done={one(params.listo)} error={one(params.error)} />
      <div className="flex max-w-3xl flex-col gap-5 px-4 pb-5 sm:px-6 sm:pb-6">
        <AiConnection status={ai} usage={usage} />
        <BusinessConnections connections={connections} boards={projects.map((p) => ({ key: p.key, name: p.name }))} done={one(params.sistema)} error={one(params.errorSistema)} />
        <TelegramConnection setup={telegram} />
      </div>
    </>
  );
}
