import type { Metadata } from "next";

import { DraftingAssistant } from "@/components/docs/DraftingAssistant";
import { PageBody } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireEditor } from "@/lib/require-editor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Redactar con IA" };

/** El asistente para redactar los documentos base (software) o el objetivo del tablero (los demás). */
export default async function DraftPage({ params }: PageProps<"/projects/[key]/docs/draft">) {
  const key = (await params).key.toUpperCase();
  await requireEditor(`/projects/${key}`);
  let data;
  try {
    data = await Promise.all([boardApi.draftPlan(key), boardApi.runnerMachines().catch(() => []), boardApi.me()]);
  } catch (e) {
    return failPage(e);
  }
  const [plan, machines, me] = data;
  const software = plan.projectKind === "SOFTWARE";
  return (
    <PageBody>
      <div className="mb-5 max-w-3xl">
        <h2 className="text-base font-semibold text-fg-strong">{software ? "Redactar los documentos base con IA" : "Proponer el objetivo del tablero con IA"}</h2>
        <p className="mt-1 text-[13px] leading-relaxed text-fg-muted">
          {software
            ? `Escribe la idea, responde unas preguntas y la IA arma ${plan.methodology === "ODD" ? "lo mínimo de ODD (Producto y Reglas para agentes)" : "los documentos de tu metodología"} con las plantillas de tu catálogo. Los pules conversando y, al aprobar, también queda el objetivo del tablero.`
            : "Cuéntale qué quieres lograr con este tablero y propone su objetivo: los agentes lo leen con cada tarjeta."}
        </p>
      </div>
      {software && !plan.methodology ? (
        <p className="text-[13px] text-fg-muted">Primero elige la metodología del tablero (Configuración → Metodología).</p>
      ) : (
        <DraftingAssistant projectKey={key} plan={plan} machineOnline={machines.some((m) => m.memberId === me.id && m.online && !m.paused)} />
      )}
    </PageBody>
  );
}
