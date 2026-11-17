import type { BoardColumn, Issue, Project, ReviewMode, StageDef } from "@/lib/types";

/** Configuración de etapa de una columna (o nada: columna libre). */
export function stageOf(project: Project, column: string): StageDef | undefined {
  return project.stages.find((s) => s.column === column);
}

/** Si el flujo deja pasar de una columna a otra (igual que la API). */
export function canMove(project: Project, from: string, to: string): boolean {
  const t = project.transitions;
  return !t.restricted || from === to || t.allowed.some((x) => x.from === from && x.to === to);
}

/** La etapa siguiente al entregar: la primera columna posterior a la que el flujo deja pasar. */
export function nextColumn(project: Project, from: string): BoardColumn | undefined {
  const i = project.columns.findIndex((c) => c.key === from);
  return project.columns.slice(i + 1).find((c) => canMove(project, from, c.key));
}

/** «Backend y Frontend», o el texto de vacío si la lista no tiene roles. */
export function roleList(keys: string[], roleName: (key: string) => string, empty = ""): string {
  const names = keys.map(roleName);
  if (names.length === 0) return empty;
  return names.length === 1 ? names[0] : `${names.slice(0, -1).join(", ")} y ${names.at(-1)}`;
}

/** Un issue sin asignar en una etapa con responsables (o en una puerta) está «por tomar». */
export function waitingToBeTaken(project: Project, column: string, assigneeId: string | null): boolean {
  const s = stageOf(project, column);
  return !assigneeId && !!s && (s.gate || s.roles.length > 0);
}

/** Quién cierra una puerta para esta tarjeta: lo del tablero, salvo que la tarjeta pida una persona o el agente. */
export function reviewFor(stage: StageDef | undefined, reviewBy: Issue["reviewBy"]): ReviewMode {
  if (!stage?.gate) return "PERSON";
  const board = stage.review?.mode ?? "PERSON";
  if (reviewBy === "PERSON") return "PERSON";
  if (reviewBy === "AGENT" && board === "PERSON") return "AGENT_ESCALATE";
  return board;
}

/** El rol del agente revisor de una puerta. */
export function reviewerRole(stage: StageDef | undefined): string {
  return stage?.review?.reviewerRole || "PR_REVIEWER";
}

