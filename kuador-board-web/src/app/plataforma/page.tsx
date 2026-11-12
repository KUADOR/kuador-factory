import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { AiPricesEditor } from "@/components/billing/AiPricesEditor";
import { AiProvidersEditor } from "@/components/billing/AiProvidersEditor";
import { LicensesTable } from "@/components/billing/LicensesTable";
import { PackagesEditor } from "@/components/catalog/PackagesEditor";
import { Button } from "@/components/ui/Button";
import { PaymentProvidersEditor } from "@/components/billing/PaymentProvidersEditor";
import { PlansEditor } from "@/components/billing/PlansEditor";
import { ReminderSettings } from "@/components/billing/ReminderSettings";
import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Plataforma" };

/** Solo para quien administra la plataforma: planes que se venden y la licencia de cada espacio. */
export default async function PlatformPage() {
  const me = await boardApi.me().catch(() => null);
  if (!me?.platformAdmin) notFound();
  let data;
  try {
    const [plans, workspaces, providers, types, settings, packages, templates, prices, aiProviders] = await Promise.all([
      boardApi.platformPlans(),
      boardApi.platformWorkspaces(),
      boardApi.paymentProviders(),
      boardApi.paymentTypes(),
      boardApi.billingSettings(),
      boardApi.platformPackages(),
      boardApi.platformCatalog("workflow-templates"),
      boardApi.aiPrices(),
      boardApi.aiProviders(),
    ]);
    data = { plans, workspaces, providers, types, settings, packages, templates, prices, aiProviders };
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader
        title="Plataforma"
        actions={
          <Button href="/plataforma/catalogo">Catálogo de la plataforma</Button>
        }
      />
      <div className="flex flex-col gap-6 px-6 py-6">
        <PlansEditor plans={data.plans} />
        <PaymentProvidersEditor providers={data.providers} types={data.types} />
        <ReminderSettings days={data.settings.reminderDays} />
        <AiProvidersEditor providers={data.aiProviders} />
        <AiPricesEditor prices={data.prices} models={data.aiProviders.flatMap((p) => p.models)} />
        <PackagesEditor
          packages={data.packages}
          templates={data.templates.filter((t) => t.status !== "NEW").map((t) => ({ key: t.key, name: t.entry.name }))}
        />
        <LicensesTable workspaces={data.workspaces} plans={data.plans} />
      </div>
    </>
  );
}
