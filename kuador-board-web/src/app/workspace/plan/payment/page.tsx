import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { Section } from "@/components/ui/Section";
import { dateLabel, priceLabel } from "@/components/billing/format";
import { boardApi } from "@/lib/api";
import { requireOwner } from "@/lib/require-editor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Pago" };

/** Vuelta de la pasarela: se confirma el cobro (si ya se confirmó por aviso, no se cobra de nuevo) y se muestra el resultado. */
export default async function PaymentReturnPage({ searchParams }: { searchParams: Promise<{ id?: string }> }) {
  await requireOwner();
  const { id } = await searchParams;
  let title = "No encontramos ese pago";
  let detail = "Vuelve a la página del plan para revisar su estado.";
  let ok = false;
  if (typeof id === "string" && id) {
    try {
      const payment = await boardApi.confirmPayment(id);
      if (payment.status === "PAID") {
        const plan = await boardApi.workspacePlan();
        ok = true;
        title = "Pago recibido";
        detail = `Pagaste ${priceLabel({ priceMonthly: Number(payment.amount), currency: payment.currency })} por ${payment.months === 1 ? "1 mes" : `${payment.months} meses`}.`
          + (plan?.paidUntil ? ` Tu plan ${plan.plan.name} está activo hasta el ${dateLabel(plan.paidUntil)}.` : "");
      } else {
        title = "El pago todavía no se completó";
        detail = "Si lo aprobaste en la pasarela, se activará en unos minutos. Si lo cancelaste, no se cobró nada.";
      }
    } catch (e) {
      title = "No pudimos confirmar el pago";
      detail = e instanceof Error ? e.message : detail;
    }
  }
  return (
    <>
      <PageHeader title="Pago" />
      <div className="px-4 py-5 sm:px-6 sm:py-6">
        <Section title={title} className="max-w-2xl">
          <div className="flex flex-col items-start gap-4">
            <p className="text-[13px] leading-relaxed text-fg">{detail}</p>
            <Button href="/workspace/plan" tone={ok ? "primary" : "secondary"}>Ir al plan</Button>
          </div>
        </Section>
      </div>
    </>
  );
}
