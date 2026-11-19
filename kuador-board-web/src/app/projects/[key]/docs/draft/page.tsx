import type { Metadata } from "next";

import { DraftingAssistant } from "@/components/docs/DraftingAssistant";
import { PageBody } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireEditor } from "@/lib/require-editor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Redactar con IA" };

/** El asistente para redactar los documentos base (software) o el objetivo del tablero (los demás). */
export default async function DraftPage({ params, searchParams }: PageProps<"/projects/[key]/docs/draft">) {
  const key = (await params).key.toUpperCase();
  const { origen } = await searchParams;
  await requireEditor(`/projects/${key}`);
  let data;
  try {
    data = await Promise.all([boardApi.draftPlan(key), boardApi.runnerMachines().catch(() => []), boardApi.me()]);
  } catch (e) {
    return failPage(e);
  }
  const [plan, machines, me] = data;
  // Software sin metodología: como en los demás tableros, se redacta el objetivo (es lo mínimo para crear el repo).
  const software = plan.projectKind === "SOFTWARE" && !!plan.methodology;
  return (
    <PageBody>
      <div className="mb-5 max-w-3xl">
        <h2 className="text-base font-semibold text-fg-strong">
          {!software ? "Objetivo del tablero" : origen === "existente" ? "Leer el proyecto" : "Documentos base"}
        </h2>
        {software && origen === "existente" && <p className="mt-1 text-[13px] text-fg-muted">El agente lee el código sin cambiar nada.</p>}
      </div>
      <DraftingAssistant projectKey={key} plan={plan} machineOnline={machines.some((m) => m.memberId === me.id && m.online && !m.paused)}
        origin={origen === "existente" ? "existing" : undefined}
        returnTo={origen === "asistente" ? `/projects/${key}/setup?step=repository` : undefined}
      />
    </PageBody>
  );
}
