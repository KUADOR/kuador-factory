import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { PaymentHistory } from "@/components/billing/PaymentHistory";
import { PlanChooser } from "@/components/billing/PlanChooser";
import { EmptyState } from "@/components/ui/Section";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireEditor } from "@/lib/require-editor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Plan" };

export default async function WorkspacePlanPage({ searchParams }: { searchParams: Promise<{ pago?: string; bienvenida?: string }> }) {
  await requireEditor();
  const params = await searchParams;
  let data;
  try {
    const [current, plans, projects, members, options, payments] = await Promise.all([
      boardApi.workspacePlan(),
      boardApi.plans(),
      boardApi.projects(),
      boardApi.members(),
      boardApi.paymentOptions().catch(() => []),
      boardApi.payments().catch(() => []),
    ]);
    data = { current, plans, options, payments, projects: projects.length, people: members.filter((m) => m.kind === "HUMAN").length };
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader title="Plan" />
      <div className="flex flex-col gap-6 px-6 py-6">
        {data.current ? (
          <>
            <PlanChooser
              current={data.current}
              plans={data.plans}
              options={data.options}
              usage={{ people: data.people, projects: data.projects }}
              notice={params.pago === "cancelado" ? "Cancelaste el pago: no se cobró nada." : params.bienvenida ? "Tu espacio está listo. Paga tu plan ahora o sigue con la prueba." : null}
            />
            {data.payments.length > 0 && <PaymentHistory payments={data.payments} plans={data.plans} />}
          </>
        ) : (
          <EmptyState title="Este espacio no tiene planes">Es una instalación propia: no hay límites de personas, tableros ni funciones.</EmptyState>
        )}
      </div>
    </>
  );
}
