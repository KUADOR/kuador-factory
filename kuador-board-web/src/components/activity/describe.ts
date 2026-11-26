import { PRIORITY_LABEL, parentLabel, typeLabel } from "@/lib/labels";
import type { Activity, IssueType, Member, Priority, Project } from "@/lib/types";

function fieldLabel(field: string | null, project: Project): string {
  const labels: Record<string, string> = {
    title: "el título",
    type: "el tipo",
    priority: "la prioridad",
    storyPoints: project.estimate === "HOURS" ? "las horas" : "los puntos",
    parent: `la ${parentLabel(project).toLowerCase()}`,
    labels: "las etiquetas",
    reviewBy: "quién la revisa en las aprobaciones",
  };
  return labels[field ?? ""] ?? field ?? "";
}

function value(field: string | null, raw: string | null, members: Map<string, Member>, project: Project): string {
  if (raw == null || raw === "") return "ninguno";
  switch (field) {
    case "status":
      // Nombre actual de la columna; si ya no existe, su clave.
      return project.columns.find((c) => c.key === raw)?.name ?? raw;
    case "type":
      return typeLabel(raw as IssueType, project);
    case "priority":
      return PRIORITY_LABEL[raw as Priority] ?? raw;
    case "assignee":
      return members.get(raw)?.name ?? "un miembro eliminado";
    case "reviewBy":
      return raw === "PERSON" ? "siempre una persona" : raw === "AGENT" ? "el agente revisor" : raw;
    default:
      return raw;
  }
}

/** Frase en español para una línea del historial, sin el actor (va aparte, con su avatar). */
const DOC_LABEL: Record<string, string> = {
  CONSTITUTION: "la constitución",
  PRODUCT: "el documento de producto",
  ARCHITECTURE: "la arquitectura",
  GLOSSARY: "el glosario",
  AGENT_RULES: "las reglas para agentes",
  SPEC: "la spec",
  PLAN: "el plan",
  TASKS: "las tareas",
  TRACKING: "el seguimiento",
  NOTES: "las notas",
};

export function describe(
  a: Activity,
  members: Map<string, Member>,
  project: Project,
): { text: string; quote?: string; href?: string } {
  const v = (raw: string | null) => value(a.field, raw, members, project);
  switch (a.type) {
    case "CREATED":
      return { text: `lo creó en ${v(a.to)}` };
    case "MOVED":
      return { text: `lo movió de ${v(a.from)} a ${v(a.to)}` };
    case "ASSIGNED":
      if (!a.to) return { text: `quitó a ${v(a.from)} como responsable` };
      if (!a.from) return { text: `lo asignó a ${v(a.to)}` };
      return { text: `lo reasignó de ${v(a.from)} a ${v(a.to)}` };
    case "COMMENTED":
      return { text: "comentó", quote: a.to ?? undefined };
    case "DELETED":
      return { text: `eliminó la tarjeta «${a.from ?? ""}»` };
    case "DOCUMENT": {
      // from = id del documento, to = versión guardada.
      const doc = DOC_LABEL[a.field ?? ""] ?? "un documento";
      const href = a.from ? `/projects/${project.key}/docs/${a.from}` : undefined;
      return a.to === "1" ? { text: `creó ${doc}`, href } : { text: `actualizó ${doc} (v${a.to})`, href };
    }
    case "UPDATED":
      if (a.field === "description") return { text: "actualizó la descripción" };
      return { text: `cambió ${fieldLabel(a.field, project)} de ${v(a.from)} a ${v(a.to)}` };
  }
}
