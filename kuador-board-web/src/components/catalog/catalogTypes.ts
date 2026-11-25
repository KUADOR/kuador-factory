import type { CatalogType } from "@/lib/types";

/** Secciones del catálogo: ruta corta en la URL ↔ tipo de la API. */
export const CATALOG_SECTIONS = [
  { slug: "templates", type: "workflow-templates", label: "Plantillas de flujo", singular: "plantilla", newLabel: "Nueva plantilla", description: "Pasos y tipos de trabajo con los que nace un tablero.", scope: "Solo afecta a los tableros nuevos: cada tablero guarda su copia.", software: false },
  { slug: "methodologies", type: "methodologies", label: "Manuales de trabajo", singular: "manual de trabajo", newLabel: "Nuevo manual de trabajo", description: "Qué documentos pide un tablero y quién los escribe.", scope: "Cambia al instante en todos los tableros que lo usan.", software: false },
  { slug: "roles", type: "roles", label: "Roles de agentes", singular: "rol", newLabel: "Nuevo rol", description: "Instrucciones y herramientas de cada especialista.", scope: "Cambia al instante en todos los tableros que lo usan.", software: false },
  { slug: "components", type: "component-templates", label: "Piezas", singular: "pieza", newLabel: "Nueva pieza", description: "APIs, webs y servicios con su código base y comandos.", scope: "Solo afecta a las piezas nuevas; comandos y herramientas llegan con el próximo kit.", software: true },
  { slug: "rules", type: "agent-policies", label: "Reglas de agentes", singular: "reglas", newLabel: "", description: "Reglas comunes, límites de costo y textos de todos los agentes.", scope: "Cambia al instante en todos los tableros que lo usan.", software: false },
  { slug: "mcp", type: "mcp-servers", label: "Herramientas del cliente de IA", singular: "herramienta", newLabel: "Nueva herramienta", description: "Servidores MCP que usa el cliente de IA de quien programa (Claude Code, Codex).", scope: "Llegan al repositorio con el próximo kit.", software: true },
] as const satisfies readonly { slug: string; type: CatalogType; label: string; singular: string; newLabel: string; description: string; scope: string; software: boolean }[];

/** Las plantillas que arma el diseñador con IA para un tablero concreto: no se ofrecen al crear otros. */
export const isGeneratedTemplate = (e: { key: string; builtIn?: boolean }) => !e.builtIn && e.key.startsWith("FLUJO_");

export type CatalogSection = (typeof CATALOG_SECTIONS)[number];

export function sectionBySlug(slug: string): CatalogSection | undefined {
  return CATALOG_SECTIONS.find((s) => s.slug === slug);
}

/** Misma regla que la API: mayúsculas, números o guion bajo, empezando por letra. */
export const KEY_PATTERN = "[A-Z][A-Z0-9_]{1,39}";
