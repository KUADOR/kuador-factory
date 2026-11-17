import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireAdmin } from "@/lib/require-editor";

import { AiConnection } from "./AiConnection";
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
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [github, workspace, telegram, ai, usage] = data;
  const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? null;
  return (
    <>
      <PageHeader title="Conexiones" width="narrow" description="Servicios externos con los que trabaja este espacio." />
      <GitHubConnections overview={github} workspaceName={workspace.name} done={one(params.listo)} error={one(params.error)} />
      <div className="flex max-w-3xl flex-col gap-5 px-6 pb-6">
        <AiConnection status={ai} usage={usage} />
        <TelegramConnection setup={telegram} />
      </div>
    </>
  );
}
