import type { Metadata } from "next";

import { AiPricesEditor } from "@/components/billing/AiPricesEditor";
import { AiProvidersEditor } from "@/components/billing/AiProvidersEditor";
import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Inteligencia artificial" };

export default async function PlatformAiPage() {
  let data;
  try {
    const [providers, prices] = await Promise.all([boardApi.aiProviders(), boardApi.aiPrices()]);
    data = { providers, prices };
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader title="Inteligencia artificial" description="Los proveedores que pueden elegir las empresas y el precio de cada modelo para estimar su consumo." />
      <div className="flex flex-col gap-6 px-4 py-5 sm:px-6 sm:py-6">
        <AiProvidersEditor providers={data.providers} />
        <AiPricesEditor prices={data.prices} models={data.providers.flatMap((p) => p.models)} />
      </div>
    </>
  );
}
