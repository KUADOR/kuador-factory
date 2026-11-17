import type { NotificationView, Project, RepositoryLink, RunnerToken } from "./types";

export type StepId = "methodology" | "repository" | "client" | "ai" | "notifications" | "firstWork";

/**
 * Pasos del asistente de inicio según el tipo de proyecto. En software los agentes trabajan en la PC de cada persona
 * (su cliente de IA); en los demás, en el tablero con la clave de IA de la empresa.
 */
export function setupSteps(project: Pick<Project, "kind">): StepId[] {
  return project.kind === "SOFTWARE" ? ["methodology", "repository", "client", "notifications", "firstWork"] : ["ai", "notifications", "firstWork"];
}

/** Qué pasos ya están hechos: lo mide igual el asistente y el aviso del tablero. */
export function setupDone(
  project: Pick<Project, "methodology">,
  link: RepositoryLink | null,
  connections: RunnerToken[],
  notifications: NotificationView | null,
  issues: number,
  aiConfigured = false,
): Record<StepId, boolean> {
  const s = notifications?.settings;
  return {
    methodology: project.methodology != null,
    repository: !!link?.linked,
    client: connections.length > 0,
    ai: aiConfigured,
    notifications: (notifications?.pushDevices ?? 0) > 0 || (!!s && ((s.email && !!notifications?.emailAvailable) || (s.telegram && s.telegramChatId != null))),
    firstWork: issues > 0,
  };
}
