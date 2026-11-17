import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { DecisionsView } from "./DecisionsView";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Mis decisiones" };

/** Lo que espera a quien mira en todos sus tableros: aprobar, repartir o responder a un agente. */
export default async function DecisionsPage() {
  let decisions;
  try {
    decisions = await boardApi.decisions();
  } catch (e) {
    return failPage(e);
  }
  return (
    <div className="mx-auto w-full max-w-2xl">
      <PageHeader title="Mis decisiones" description="Lo que espera por ti en todos tus tableros. Lo demás lo siguen los agentes." />
      <DecisionsView decisions={decisions} />
    </div>
  );
}
