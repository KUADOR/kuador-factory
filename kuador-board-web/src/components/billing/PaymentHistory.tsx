import { Section } from "@/components/ui/Section";
import type { Payment, Plan } from "@/lib/types";

import { dateLabel, priceLabel } from "./format";

const STATUS: Record<Payment["status"], string> = { PAID: "Pagado", CREATED: "Sin completar", FAILED: "No se pudo iniciar" };

/** Últimos pagos del espacio. El recibo de cada pago confirmado llega por correo a los dueños y a quien pagó. */
export function PaymentHistory({ payments, plans }: { payments: Payment[]; plans: Plan[] }) {
  return (
    <Section title="Pagos" description="El recibo llega por correo a los dueños y a quien pagó." flush>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] text-sm">
          <thead>
            <tr className="border-b border-line text-left text-[12px] text-fg-muted">
              <th className="px-5 py-2 font-medium">Fecha</th>
              <th className="px-3 py-2 font-medium">Plan</th>
              <th className="px-3 py-2 font-medium">Periodo</th>
              <th className="px-3 py-2 font-medium">Monto</th>
              <th className="px-5 py-2 font-medium">Estado</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {payments.map((p) => (
              <tr key={p.id}>
                <td className="tabular px-5 py-2.5 text-fg">{dateLabel(p.paidAt ?? p.createdAt)}</td>
                <td className="px-3 py-2.5 text-fg">{plans.find((x) => x.code === p.planCode)?.name ?? p.planCode}</td>
                <td className="px-3 py-2.5 text-fg-muted">{p.months === 1 ? "1 mes" : `${p.months} meses`}</td>
                <td className="tabular px-3 py-2.5 text-fg">{priceLabel({ priceMonthly: Number(p.amount), currency: p.currency })}</td>
                <td className={p.status === "PAID" ? "px-5 py-2.5 text-ok" : "px-5 py-2.5 text-fg-muted"}>{STATUS[p.status]}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Section>
  );
}
