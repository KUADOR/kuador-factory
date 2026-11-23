import type { Capability, ColumnCategory, EstimateUnit, FieldDef, FieldKind, DocumentDef, DocumentScope, IssueType, Methodology, Priority, Project, ProjectKind } from "./types";

/** Lo que define cómo se nombran los issues de un proyecto: viene de su plantilla del catálogo. */
export type TypeSource = Pick<Project, "kind" | "issueTypes">;

export const PROJECT_KIND_LABEL: Record<ProjectKind, string> = {
  SOFTWARE: "Software",
  GENERAL: "General",
};

export const CATEGORY_LABEL: Record<ColumnCategory, string> = {
  TODO: "Por empezar",
  IN_PROGRESS: "En curso",
  DONE: "Terminado",
};

const DEFAULT_TYPE_LABEL: Record<IssueType, string> = { EPIC: "Épica", STORY: "Historia", TASK: "Tarea", BUG: "Bug" };

/** Tipos que admite el proyecto, en el orden de su plantilla. */
export function allowedTypes(source: TypeSource): IssueType[] {
  return source.issueTypes.map((t) => t.type);
}

export function typeLabel(type: IssueType, source?: TypeSource): string {
  return source?.issueTypes.find((t) => t.type === type)?.label ?? DEFAULT_TYPE_LABEL[type] ?? type;
}

/** Cómo se llama la épica (padre) en el proyecto: «Épica», «Campaña», «Grupo»… */
export function parentLabel(source: TypeSource): string {
  return typeLabel("EPIC", source);
}

export const PRIORITY_LABEL: Record<Priority, string> = {
  HIGHEST: "Máxima",
  HIGH: "Alta",
  MEDIUM: "Media",
  LOW: "Baja",
  LOWEST: "Mínima",
};

export function initials(name: string): string {
  const words = name
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .split(/\s+/)
    .filter((w) => w && w.toLowerCase() !== "agente");
  return (words[0]?.[0] ?? "?").toUpperCase() + (words[1]?.[0] ?? "").toUpperCase();
}

/** Documentos que pide una metodología, del proyecto o de cada issue. */
export function documentsOf(methodology: Methodology | null | undefined, scope: DocumentScope): DocumentDef[] {
  return methodology?.documents.filter((d) => d.scope === scope) ?? [];
}

/** Roles de personas que trae el tablero; los de agentes vienen con nombre desde el catálogo. */
const HUMAN_ROLE_LABEL: Record<string, string> = { OWNER: "Dueño del espacio", CLIENT: "Cliente", CLIENTE: "Cliente" };

/** Nombre legible de un rol: el del catálogo si existe; si no, la clave en minúsculas («QA_LEAD» → «Qa lead»). */
export function roleLabel(key: string, catalogName?: string): string {
  if (catalogName) return catalogName;
  if (HUMAN_ROLE_LABEL[key]) return HUMAN_ROLE_LABEL[key];
  const words = key.toLowerCase().replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** Roles que pueden cubrir etapas: los del catálogo (agentes) y los de las personas del espacio. */
export function stageRoleOptions(catalogRoles: { key: string; name: string }[], humanRoles: string[]): { value: string; label: string }[] {
  const human = [...new Set(["OWNER", ...humanRoles])].filter((k) => !catalogRoles.some((r) => r.key === k));
  return [...catalogRoles.map((r) => ({ value: r.key, label: r.name })), ...human.map((k) => ({ value: k, label: roleLabel(k) }))];
}

/**
 * Los modelos de Claude con una pista de para qué sirve cada uno: el más capaz para lógica y arquitectura, el
 * equilibrado para análisis y redacción (PO, marketing), el económico para tareas simples y de mucho volumen.
 */
/** Cuánto razona el modelo: más esfuerzo = mejores respuestas en lo difícil, más tokens y más tiempo. */
export const EFFORT_LABEL: Record<string, string> = {
  low: "Bajo (tareas simples, el más barato)",
  medium: "Medio (la mayoría del trabajo)",
  high: "Alto (diseño y problemas difíciles)",
  xhigh: "Muy alto",
  max: "Máximo (el más caro)",
};

export const MODEL_LABEL: Record<string, string> = {
  "claude-opus-5-5": "Claude Opus 5.5 (el más capaz: lógica, arquitectura)",
  "claude-sonnet-5-5": "Claude Sonnet 5.5 (equilibrado: análisis, redacción)",
  "claude-haiku-5-5": "Claude Haiku 5.5 (rápido y económico: tareas simples)",
};

/** Lo que un tablero puede usar, con palabras de quien lo configura. */
export const CAPABILITY_LABEL: Record<Capability, { label: string; hint: string }> = {
  DOCUMENTS: { label: "Documentos", hint: "Manual de trabajo y documentos con versiones." },
  REPOSITORY: { label: "Repositorio de código", hint: "Piezas, Pull Requests y CI en GitHub." },
  SPLIT: { label: "Reparto en tareas", hint: "Un trabajo grande se divide en tareas con responsable." },
};

/** Si el tablero usa una capacidad. */
export function can(project: Pick<Project, "capabilities">, capability: Capability): boolean {
  return project.capabilities?.includes(capability) ?? false;
}

/** Cómo se llama el tamaño de una tarjeta según la unidad del tablero (NONE no se muestra). */
export const ESTIMATE_LABEL: Record<EstimateUnit, string> = { POINTS: "Puntos", HOURS: "Horas", NONE: "Sin estimación" };

export const FIELD_KIND_LABEL: Record<FieldKind, string> = { TEXT: "Texto", NUMBER: "Número", MONEY: "Dinero", DATE: "Fecha", CHOICE: "Lista" };

/** Los datos propios de un tipo de tarjeta en el tablero. */
export function fieldsOf(project: Pick<Project, "issueTypes">, type: IssueType): FieldDef[] {
  return project.issueTypes.find((t) => t.type === type)?.fields ?? [];
}

/** Un valor guardado, como se le muestra a una persona. */
export function fieldText(def: FieldDef, value: string | undefined): string {
  if (!value) return "";
  if (def.kind === "MONEY") return new Intl.NumberFormat("es-EC", { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(value));
  if (def.kind === "DATE") return new Intl.DateTimeFormat("es-EC", { dateStyle: "medium", timeZone: "UTC" }).format(new Date(`${value}T00:00:00Z`));
  return value;
}
