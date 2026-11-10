import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { WorkspaceSettings } from "@/components/workspace/WorkspaceSettings";
import { boardApi } from "@/lib/api";
import { requireEditor } from "@/lib/require-editor";
import { failPage } from "@/lib/page-errors";
import type { Workspace } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Espacio de trabajo" };

export default async function WorkspacePage() {
  await requireEditor();
  let workspace: Workspace;
  try {
    workspace = await boardApi.workspace();
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader title="Marca y empresa" />
      <div className="px-6 py-6">
        <WorkspaceSettings workspace={workspace} />
      </div>
    </>
  );
}
