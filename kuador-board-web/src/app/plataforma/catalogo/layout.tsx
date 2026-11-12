import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { CatalogTabs } from "@/components/catalog/CatalogTabs";
import { PublishCatalog } from "@/components/catalog/PublishCatalog";
import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Catálogo de la plataforma" };

/** Catálogo de la plataforma: lo que reciben todos los espacios. Solo para quien administra Kuador Go. */
export default async function PlatformCatalogLayout({ children }: LayoutProps<"/plataforma/catalogo">) {
  const me = await boardApi.me().catch(() => null);
  if (!me?.platformAdmin) notFound();
  const summary = await boardApi.platformCatalogSummary();
  return (
    <div className="flex min-h-full flex-col">
      <PageHeader
        title="Catálogo de la plataforma"
        description="Plantillas, metodologías, roles, piezas y servidores MCP que reciben todos los espacios. Los cambios quedan en borrador hasta publicar."
        actions={<PublishCatalog summary={summary} />}
      >
        <CatalogTabs base="/plataforma/catalogo" />
      </PageHeader>
      {children}
    </div>
  );
}
