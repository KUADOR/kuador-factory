"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { changePlanAction, checkoutAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { PaymentOption, Plan, WorkspacePlan } from "@/lib/types";

import { PlanCard } from "./PlanCard";
import { STATUS_LABEL, dateLabel, priceLabel } from "./format";

/** Plan del espacio: estado de la licencia, uso frente a los límites y cambio de plan. */
export function PlanChooser({
  current,
  plans,
  options,
  usage,
  notice,
}: {
  current: WorkspacePlan;
  plans: Plan[];
  options: PaymentOption[];
  usage: { people: number; projects: number };
  notice: string | null;
}) {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [target, setTarget] = useState<string | null>(null);
  const [months, setMonths] = useState<1 | 12>(1);

  /** Lleva a la pasarela a pagar {@code months} meses de {@code p}; al pagar, el espacio queda en ese plan. */
  const pay = (p: Plan, provider: PaymentOption) => {
    setTarget(`${p.code}:${provider.code}`);
    start(async () => {
      const r = await checkoutAction({ plan: p.code, provider: provider.code, months });
      if (!r.ok) {
        notify.error("No se pudo iniciar el pago", r.error);
        return;
      }
      window.location.assign(r.data.payUrl);
    });
  };
  const payButtons = (p: Plan) =>
    options.map((o) => (
      <Button key={o.code} tone="primary" className="w-full" loading={pending && target === `${p.code}:${o.code}`} disabled={pending} onClick={() => pay(p, o)}>
        Pagar {months === 12 ? "12 meses" : "1 mes"} con {o.name}
      </Button>
    ));
  const { plan, effective, status } = current;
  const downgraded = effective.code !== plan.code;

  const change = (p: Plan) => {
    setTarget(p.code);
    start(async () => {
      const r = await changePlanAction(p.code);
      if (!r.ok) {
        notify.error(r.error);
        return;
      }
      const s = r.data.status;
      notify.ok(
        s === "TRIAL"
          ? `Empezó tu prueba de ${p.name}`
          : s === "PENDING_PAYMENT"
            ? `${p.name} queda pendiente de pago`
            : `Ahora estás en ${p.name}`,
      );
      router.refresh();
    });
  };

  return (
    <>
      {notice && <p role="status" className="rounded-md bg-info/10 px-3 py-2 text-[13px] text-fg">{notice}</p>}
      <Section
        title={`Plan ${plan.name}`}
        description={statusText(current)}
        actions={
          options.length > 0 && (
            <div role="radiogroup" aria-label="Periodo de pago" className="flex rounded-md border border-line p-0.5 text-[12px]">
              {([1, 12] as const).map((m) => (
                <button
                  key={m}
                  type="button"
                  role="radio"
                  aria-checked={months === m}
                  onClick={() => setMonths(m)}
                  className={months === m ? "rounded bg-selected px-2.5 py-1 font-medium text-fg-strong" : "rounded px-2.5 py-1 text-fg-muted hover:text-fg"}
                >
                  {m === 1 ? "Mensual" : "Anual"}
                </button>
              ))}
            </div>
          )
        }
      >
        <dl className="grid max-w-2xl grid-cols-2 gap-4 sm:grid-cols-4">
          <Stat label="Estado" value={STATUS_LABEL[status]} />
          <Stat label="Precio" value={plan.priceMonthly > 0 ? `${priceLabel(plan)} al mes` : "Gratis"} />
          <Stat label="Personas" value={`${usage.people} de ${effective.maxMembers ?? "∞"}`} />
          <Stat label="Tableros" value={`${usage.projects} de ${effective.maxProjects ?? "∞"}`} />
        </dl>
        {plan.priceMonthly > 0 && options.length > 0 && (
          <div className="mt-4 flex max-w-md flex-col gap-2">{payButtons(plan)}</div>
        )}
        {downgraded && (
          <p role="status" className="mt-4 rounded-md bg-warn/10 px-3 py-2 text-[13px] text-fg">
            Mientras tanto rigen los límites de {effective.name}. Nada se borra: solo no puedes crear más de lo que ese plan permite.
          </p>
        )}
      </Section>

      <ul className={`grid gap-4 ${plans.length >= 3 ? "xl:grid-cols-3" : "lg:grid-cols-2"}`}>
        {plans.map((p) => {
          const isCurrent = p.code === plan.code;
          return (
            <li key={p.code}>
              <PlanCard
                plan={p}
                current={isCurrent}
                action={
                  isCurrent ? undefined : p.priceMonthly === 0 ? (
                    <Button className="w-full" loading={pending && target === p.code} disabled={pending} onClick={() => change(p)}>
                      Cambiar a {p.name}
                    </Button>
                  ) : (
                    <>
                      {options.length > 0 && payButtons(p)}
                      {(!current.trialUsed && p.trialDays > 0) || options.length === 0 ? (
                        <Button tone={options.length > 0 ? "ghost" : "primary"} className="w-full" loading={pending && target === p.code} disabled={pending} onClick={() => change(p)}>
                          {!current.trialUsed && p.trialDays > 0 ? `Probar ${p.trialDays} días` : `Elegir ${p.name}`}
                        </Button>
                      ) : null}
                    </>
                  )
                }
              />
            </li>
          );
        })}
      </ul>
      <p className="max-w-3xl text-[13px] text-fg-muted">
        {options.length > 0
          ? "Pagas por mes o por año; al pagar, el plan queda activo hasta la nueva fecha. Cada espacio tiene una sola prueba de un plan de pago."
          : "Cada espacio tiene una sola prueba de un plan de pago. El pago en línea todavía no está habilitado; escríbenos desde kuador.com para activarlo."}
      </p>
    </>
  );
}

function statusText(c: WorkspacePlan): string {
  switch (c.status) {
    case "TRIAL":
      return c.trialEndsAt && new Date(c.trialEndsAt) > new Date()
        ? `Tu prueba termina el ${dateLabel(c.trialEndsAt)}.`
        : "Tu prueba terminó. Activa el pago para seguir con este plan.";
    case "ACTIVE":
      return c.paidUntil ? `Pagado hasta el ${dateLabel(c.paidUntil)}.` : "Activo.";
    case "PENDING_PAYMENT":
      return "Falta activar el pago de este plan.";
    case "CANCELED":
      return "El plan está cancelado.";
  }
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-[12px] text-fg-muted">{label}</dt>
      <dd className="tabular mt-0.5 text-sm font-medium text-fg-strong">{value}</dd>
    </div>
  );
}
