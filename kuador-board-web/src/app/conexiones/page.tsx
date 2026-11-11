import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireEditor } from "@/lib/require-editor";

import { GitHubConnections } from "./GitHubConnections";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Conexiones" };

export default async function ConnectionsPage({ searchParams }: PageProps<"/conexiones">) {
  await requireEditor();
  const params = await searchParams;
  let data;
  try {
    data = await Promise.all([boardApi.github(), boardApi.workspace()]);
  } catch (e) {
    return failPage(e);
  }
  const [github, workspace] = data;
  const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? null;
  return (
    <>
      <PageHeader title="Conexiones" width="narrow" description="Servicios externos con los que trabaja este espacio." />
      <GitHubConnections overview={github} workspaceName={workspace.name} done={one(params.listo)} error={one(params.error)} />
    </>
  );
}
