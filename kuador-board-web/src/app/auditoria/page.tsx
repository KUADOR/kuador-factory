import type { Metadata } from "next";

import { AuditView } from "@/components/audit/AuditView";
import { auditQuery } from "@/lib/audit";
import { PageBody, PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireEditor } from "@/lib/require-editor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Auditoría" };

export default async function AuditPage({ searchParams }: { searchParams: Promise<{ ver?: string; q?: string; desde?: string }> }) {
  await requireEditor();
  const params = await searchParams;
  let page;
  try {
    page = await boardApi.workspaceAudit(auditQuery(params));
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader title="Auditoría" description="Quién hizo qué, cuándo y desde dónde. No se puede editar ni borrar." />
      <PageBody>
        {/* La clave fuerza a reiniciar la lista cuando cambian los filtros. */}
        <AuditView key={JSON.stringify(params)} page={page} scope="workspace" exportHref="/auditoria/exportar" />
      </PageBody>
    </>
  );
}
