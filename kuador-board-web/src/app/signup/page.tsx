import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import type { Plan } from "@/lib/types";

import { SignupFlow } from "./SignupFlow";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Crear espacio de trabajo" };

export default async function SignupPage({ searchParams }: { searchParams: Promise<{ plan?: string }> }) {
  const { plan } = await searchParams;
  // Sin planes (la API no responde o es una instalación propia) el alta sigue sin el paso del plan.
  const plans: Plan[] = await boardApi.plans().catch(() => []);
  const chosen = plans.find((p) => p.code === plan?.toUpperCase()) ?? plans.find((p) => p.highlighted) ?? plans[0] ?? null;
  return <SignupFlow plans={plans} initialPlan={chosen?.code ?? null} />;
}
