import type { LicenseStatus, Plan } from "@/lib/types";

/** «$29», «$35,50» o «Gratis». */
export function priceLabel(plan: Pick<Plan, "priceMonthly" | "currency">): string {
  if (plan.priceMonthly === 0) return "Gratis";
  const whole = Number.isInteger(plan.priceMonthly);
  return new Intl.NumberFormat("es-EC", {
    style: "currency",
    currency: plan.currency,
    minimumFractionDigits: whole ? 0 : 2,
    maximumFractionDigits: 2,
  }).format(plan.priceMonthly);
}

export function limitLabel(plan: Pick<Plan, "maxMembers" | "maxProjects">): string {
  const people = plan.maxMembers == null ? "Personas sin límite" : `Hasta ${plan.maxMembers} ${plan.maxMembers === 1 ? "persona" : "personas"}`;
  const boards = plan.maxProjects == null ? "tableros sin límite" : `${plan.maxProjects} ${plan.maxProjects === 1 ? "tablero" : "tableros"}`;
  return `${people} · ${boards}`;
}

export const STATUS_LABEL: Record<LicenseStatus, string> = {
  TRIAL: "En prueba",
  ACTIVE: "Activo",
  PENDING_PAYMENT: "Pendiente de pago",
  CANCELED: "Cancelado",
};

export function dateLabel(iso: string): string {
  return new Intl.DateTimeFormat("es-EC", { day: "numeric", month: "long", year: "numeric" }).format(new Date(iso));
}
