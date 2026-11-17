import type { Metadata } from "next";

import { PackagesEditor } from "@/components/catalog/PackagesEditor";
import { PageBody, PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Paquetes por industria" };

export default async function PlatformPackagesPage() {
  let data;
  try {
    const [packages, templates] = await Promise.all([boardApi.platformPackages(), boardApi.platformCatalog("workflow-templates")]);
    data = { packages, templates };
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader title="Paquetes por industria" description="Con qué tablero y plantillas arranca cada empresa según a qué se dedica." />
      <PageBody>
        <PackagesEditor packages={data.packages} templates={data.templates.filter((t) => t.status !== "NEW").map((t) => ({ key: t.key, name: t.entry.name }))} />
      </PageBody>
    </>
  );
}
