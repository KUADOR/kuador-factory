import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { LanguageSettings } from "@/components/workspace/LanguageSettings";
import { WorkspaceSettings } from "@/components/workspace/WorkspaceSettings";
import { boardApi } from "@/lib/api";
import { requireAdmin } from "@/lib/require-editor";
import { failPage } from "@/lib/page-errors";
import type { Workspace } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Espacio de trabajo" };

export default async function WorkspacePage() {
  await requireAdmin();
  let workspace: Workspace;
  try {
    workspace = await boardApi.workspace();
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader title="Empresa e idiomas" />
      <div className="flex flex-col gap-6 px-6 py-6">
        <WorkspaceSettings workspace={workspace} />
        <div className="max-w-3xl">
          <LanguageSettings workspace={workspace} />
        </div>
      </div>
    </>
  );
}
