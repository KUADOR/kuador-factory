import type { Metadata } from "next";

import { PaymentProvidersEditor } from "@/components/billing/PaymentProvidersEditor";
import { PageBody, PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Pasarelas de pago" };

export default async function PlatformPaymentsPage() {
  let data;
  try {
    const [providers, types] = await Promise.all([boardApi.paymentProviders(), boardApi.paymentTypes()]);
    data = { providers, types };
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader title="Pasarelas de pago" description="Con qué se cobran los planes." />
      <PageBody>
        <PaymentProvidersEditor providers={data.providers} types={data.types} />
      </PageBody>
    </>
  );
}
