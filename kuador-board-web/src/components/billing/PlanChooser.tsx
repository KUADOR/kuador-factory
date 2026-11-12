"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { changePlanAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { Plan, WorkspacePlan } from "@/lib/types";

import { PlanCard } from "./PlanCard";
import { STATUS_LABEL, dateLabel, priceLabel } from "./format";

/** Plan del espacio: estado de la licencia, uso frente a los límites y cambio de plan. */
export function PlanChooser({ current, plans, usage }: { current: WorkspacePlan; plans: Plan[]; usage: { people: number; projects: number } }) {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [target, setTarget] = useState<string | null>(null);
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
      <Section title={`Plan ${plan.name}`} description={statusText(current)}>
        <dl className="grid max-w-2xl grid-cols-2 gap-4 sm:grid-cols-4">
          <Stat label="Estado" value={STATUS_LABEL[status]} />
          <Stat label="Precio" value={plan.priceMonthly > 0 ? `${priceLabel(plan)} al mes` : "Gratis"} />
          <Stat label="Personas" value={`${usage.people} de ${effective.maxMembers ?? "∞"}`} />
          <Stat label="Tableros" value={`${usage.projects} de ${effective.maxProjects ?? "∞"}`} />
        </dl>
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
                  isCurrent && !downgraded ? undefined : (
                    <Button tone={p.highlighted ? "primary" : "secondary"} className="w-full" loading={pending && target === p.code} disabled={pending} onClick={() => change(p)}>
                      {isCurrent ? "Reintentar" : p.priceMonthly === 0 ? `Cambiar a ${p.name}` : !current.trialUsed && p.trialDays > 0 ? `Probar ${p.name}` : `Elegir ${p.name}`}
                    </Button>
                  )
                }
              />
            </li>
          );
        })}
      </ul>
      <p className="max-w-3xl text-[13px] text-fg-muted">
        Cada espacio tiene una sola prueba de un plan de pago. Mientras habilitamos el cobro en línea, el pago se activa a mano: escríbenos desde{" "}
        <a className="k-link" href="https://kuador.com" target="_blank" rel="noreferrer">kuador.com</a>.
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
