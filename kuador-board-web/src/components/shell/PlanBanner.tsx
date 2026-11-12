"use client";

import { Clock } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";

import { dateLabel } from "@/components/billing/format";
import type { WorkspacePlan } from "@/lib/types";

import { useAccess } from "./AccessContext";

/**
 * Franja para quienes editan cuando el plan vence en los días de aviso o ya venció. No aparece en la página del
 * plan (ahí ya está todo) ni para planes gratuitos o sin vencimiento.
 */
export function PlanBanner({ plan }: { plan: WorkspacePlan }) {
  const { canEdit } = useAccess();
  const path = usePathname();
  // La hora se toma una vez al montar: la franja no necesita contar en vivo.
  const [now] = useState(() => Date.now());
  if (!canEdit || path.startsWith("/workspace/plan")) return null;

  const trial = plan.status === "TRIAL";
  const due = trial ? plan.trialEndsAt : plan.status === "ACTIVE" ? plan.paidUntil : null;
  const expired = plan.effective.code !== plan.plan.code;
  let text: string | null = null;
  if (expired) {
    text = trial
      ? `Terminó tu prueba de ${plan.plan.name}. Rige el plan ${plan.effective.name}.`
      : plan.status === "PENDING_PAYMENT"
        ? `Falta el pago del plan ${plan.plan.name}. Mientras tanto rige ${plan.effective.name}.`
        : `Venció tu plan ${plan.plan.name}. Rige el plan ${plan.effective.name}.`;
  } else if (due) {
    const days = Math.ceil((new Date(due).getTime() - now) / 86_400_000);
    if (days <= plan.warnDays) {
      text = `${trial ? `Tu prueba de ${plan.plan.name}` : `Tu plan ${plan.plan.name}`} vence el ${dateLabel(due)}${days <= 1 ? " (mañana)" : ` (en ${days} días)`}.`;
    }
  }
  if (!text) return null;

  return (
    <div role="status" className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-1 border-b border-line bg-warn/10 px-4 py-2 text-[13px] text-fg">
      <Clock className="size-4 shrink-0 text-warn" aria-hidden />
      <span className="min-w-0 flex-1">{text}</span>
      <Link href="/workspace/plan" className="k-link font-medium">
        {expired || trial ? "Pagar el plan" : "Renovar"}
      </Link>
    </div>
  );
}
