import { clsx } from "clsx";
import { Check } from "lucide-react";

import type { Plan } from "@/lib/types";

import { limitLabel, priceLabel } from "./format";

/**
 * Tarjeta de un plan: nombre, precio, para quién es y qué incluye. La usan la presentación, el alta y la página
 * del plan del espacio; la acción (enlace o botón) la pone quien la usa.
 */
export function PlanCard({
  plan,
  action,
  current,
  currentLabel = "Tu plan",
  tone = "app",
}: {
  plan: Plan;
  action?: React.ReactNode;
  current?: boolean;
  currentLabel?: string;
  tone?: "app" | "landing";
}) {
  const landing = tone === "landing";
  return (
    <div
      className={clsx(
        "relative flex h-full flex-col gap-5 rounded-xl border p-6",
        landing ? "bg-page p-8" : "bg-surface",
        plan.highlighted ? "border-sol/60" : "border-line",
        current && "ring-1 ring-sol",
      )}
    >
      <div className="flex items-center justify-between gap-3">
        <span className={clsx("font-medium text-fg-strong", landing ? "font-sans text-[1.5rem] tracking-[-0.02em] [font-stretch:116%]" : "text-base")}>{plan.name}</span>
        {current ? (
          <span className="rounded-full bg-sol px-2.5 py-0.5 text-[11px] font-semibold text-on-sol">{currentLabel}</span>
        ) : plan.highlighted ? (
          <span className="rounded-full border border-sol/50 px-2.5 py-0.5 text-[11px] font-semibold text-sol-text">Recomendado</span>
        ) : plan.factory ? (
          <span className="rounded-full border border-line-strong px-2.5 py-0.5 text-[11px] font-medium text-fg-muted">Premium</span>
        ) : null}
      </div>
      <div>
        <p className="flex items-baseline gap-1.5">
          <span className={clsx("tabular font-semibold text-fg-strong", landing ? "text-[2.5rem] leading-none tracking-[-0.03em]" : "text-2xl")}>{priceLabel(plan)}</span>
          {plan.priceMonthly > 0 && <span className="text-[13px] text-fg-muted">al mes por espacio</span>}
        </p>
        <p className="mt-2 text-[14px] leading-relaxed text-fg-muted">{plan.tagline}</p>
      </div>
      <ul className="flex flex-col gap-2.5 text-[14px] text-fg">
        <li className="flex gap-2.5 text-fg-muted">
          <Check className="mt-0.5 size-4 shrink-0 text-sol" aria-hidden />
          {limitLabel(plan)}
        </li>
        {plan.features.map((f) => (
          <li key={f} className="flex gap-2.5">
            <Check className="mt-0.5 size-4 shrink-0 text-sol" aria-hidden />
            {f}
          </li>
        ))}
      </ul>
      {(action || plan.trialDays > 0) && (
        <div className="mt-auto flex flex-col gap-2 pt-2">
          {action}
          {plan.trialDays > 0 && plan.priceMonthly > 0 && <p className="text-center text-[12px] text-fg-faint">{plan.trialDays} días de prueba</p>}
        </div>
      )}
    </div>
  );
}
