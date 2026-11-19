import type { Metadata } from "next";

import { LicensesTable } from "@/components/billing/LicensesTable";
import { PageBody, PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Espacios y licencias" };

/** Los espacios de la plataforma y la licencia de cada uno. */
export default async function PlatformWorkspacesPage() {
  let data;
  try {
    const [plans, workspaces] = await Promise.all([boardApi.platformPlans(), boardApi.platformWorkspaces()]);
    data = { plans, workspaces };
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader title="Espacios y licencias" description="Cada empresa, su plan y hasta cuándo está pagado." />
      <PageBody>
        <LicensesTable workspaces={data.workspaces} plans={data.plans} />
      </PageBody>
    </>
  );
}
