import type { Metadata } from "next";

import { AuditView } from "@/components/audit/AuditView";
import { auditQuery } from "@/lib/audit";
import { PageBody, PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Auditoría global" };

/** Toda la plataforma: también los intentos sin espacio (logins fallidos) y lo que hace quien administra. */
export default async function PlatformAuditPage({ searchParams }: { searchParams: Promise<{ ver?: string; q?: string; desde?: string; espacio?: string }> }) {
  const params = await searchParams;
  let data;
  try {
    const [page, workspaces] = await Promise.all([
      boardApi.platformAudit(auditQuery(params, params.espacio ? { workspace: params.espacio } : {})),
      boardApi.platformWorkspaces(),
    ]);
    data = { page, names: Object.fromEntries(workspaces.map((w) => [w.workspaceId, w.name])) };
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader title="Auditoría global" description="Lo que pasa en toda la plataforma, incluidos los intentos de acceso fallidos." />
      <PageBody>
        <AuditView key={JSON.stringify(params)} page={data.page} scope="platform" workspaces={data.names} />
      </PageBody>
    </>
  );
}
