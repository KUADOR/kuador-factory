import type { NotificationView, Project, RepositoryLink, RunnerToken } from "./types";

export type StepId = "metodologia" | "repositorio" | "agentes" | "ia" | "avisos" | "trabajo";

/**
 * Pasos del asistente de inicio según el tipo de proyecto. En software los agentes trabajan en la PC de cada persona
 * (su cliente de IA); en los demás, en el tablero con la clave de IA de la empresa.
 */
export function setupSteps(project: Pick<Project, "kind">): StepId[] {
  return project.kind === "SOFTWARE" ? ["metodologia", "repositorio", "agentes", "avisos", "trabajo"] : ["ia", "avisos", "trabajo"];
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
    metodologia: project.methodology != null,
    repositorio: !!link?.linked,
    agentes: connections.length > 0,
    ia: aiConfigured,
    avisos: !!s && ((s.email && !!notifications?.emailAvailable) || (s.telegram && s.telegramChatId != null)),
    trabajo: issues > 0,
  };
}
