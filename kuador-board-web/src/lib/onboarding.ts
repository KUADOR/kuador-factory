import type { DraftPlan, NotificationView, Project, RepositoryLink, RunnerToken } from "./types";

export type StepId = "methodology" | "docs" | "repository" | "client" | "ai" | "notifications" | "firstWork";

/**
 * Pasos del asistente de inicio según el tipo de proyecto. En software los agentes trabajan en la PC de cada persona
 * (su cliente de IA); en los demás, en el tablero con la clave de IA de la empresa. En software, los documentos base van
 * antes del repo: van en su primer commit y guían a los agentes.
 */
export function setupSteps(project: Pick<Project, "kind">): StepId[] {
  return project.kind === "SOFTWARE" ? ["methodology", "docs", "repository", "client", "notifications", "firstWork"] : ["ai", "notifications", "firstWork"];
}

/**
 * Las bases del proyecto están listas: los documentos base de su metodología redactados o, sin metodología, el objetivo
 * del tablero. Es lo que pide la API para crear el repo de un proyecto nuevo.
 */
export function baseReady(plan: Pick<DraftPlan, "methodology" | "pendingBase" | "goal"> | null): boolean {
  if (!plan) return false;
  return plan.methodology ? plan.pendingBase.length === 0 : !!plan.goal?.trim();
}

/** Qué pasos ya están hechos: lo mide igual el asistente y el aviso del tablero. */
export function setupDone(
  project: Pick<Project, "methodology">,
  link: RepositoryLink | null,
  connections: RunnerToken[],
  notifications: NotificationView | null,
  issues: number,
  aiConfigured = false,
  draft: DraftPlan | null = null,
): Record<StepId, boolean> {
  const s = notifications?.settings;
  return {
    methodology: project.methodology != null,
    // Con el repo ya vinculado (un proyecto que ya existía, o de antes de este paso) ya no frena nada.
    docs: baseReady(draft) || !!link?.linked,
    repository: !!link?.linked,
    client: connections.length > 0,
    ai: aiConfigured,
    notifications: (notifications?.pushDevices ?? 0) > 0 || (!!s && ((s.email && !!notifications?.emailAvailable) || (s.telegram && s.telegramChatId != null))),
    firstWork: issues > 0,
  };
}
