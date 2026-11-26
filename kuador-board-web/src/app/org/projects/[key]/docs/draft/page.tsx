import type { Metadata } from "next";

import { DraftingAssistant } from "@/components/docs/DraftingAssistant";
import { PageBody } from "@/components/shell/PageHeader";
import { boardApi, projectScope } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Redactar con IA" };

/** Redactar los documentos base del proyecto con IA (o, si ya existe, leer su código para escribirlos). */
export default async function OrgProjectDraftPage({ params, searchParams }: PageProps<"/org/projects/[key]/docs/draft">) {
  const key = (await params).key.toUpperCase();
  const { origen } = await searchParams;
  let data;
  try {
    data = await Promise.all([boardApi.draftPlan(projectScope(key)), boardApi.runnerMachines().catch(() => []), boardApi.me()]);
  } catch (e) {
    return failPage(e);
  }
  const [plan, machines, me] = data;
  return (
    <PageBody>
      <h2 className="mb-5 text-base font-semibold text-fg-strong">{origen === "existente" ? "Leer el proyecto" : "Documentos base"}</h2>
      <DraftingAssistant
        projectKey={projectScope(key)}
        plan={plan}
        machineOnline={machines.some((m) => m.memberId === me.id && m.online && !m.paused)}
        origin={origen === "existente" ? "existing" : undefined}
        returnTo={`/org/projects/${key}/docs`}
      />
    </PageBody>
  );
}
