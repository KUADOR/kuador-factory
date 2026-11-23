import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireAdmin } from "@/lib/require-editor";

import { BoardDesigner } from "./BoardDesigner";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Armar un tablero" };

/** «Cuéntame tu negocio»: la persona describe su trabajo y la IA propone el tablero; lo crea solo si le sirve. */
export default async function DesignPage() {
  await requireAdmin();
  let data;
  try {
    data = await Promise.all([boardApi.catalog("roles"), boardApi.workspaceAi().catch(() => null), boardApi.connections().catch(() => [])]);
  } catch (e) {
    return failPage(e);
  }
  const [roles, ai, connections] = data;
  return (
    <div className="mx-auto w-full max-w-2xl">
      <PageHeader title="Armar un tablero" />
      <BoardDesigner
        roles={roles.map((r) => ({ key: r.key, name: r.name }))}
        systems={connections.map((c) => ({ key: c.key, name: c.name }))}
        aiReady={!!ai?.configured}
      />
    </div>
  );
}
