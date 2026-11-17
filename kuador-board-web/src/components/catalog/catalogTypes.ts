import type { CatalogType } from "@/lib/types";

/** Secciones del catálogo: ruta corta en la URL ↔ tipo de la API. */
export const CATALOG_SECTIONS = [
  { slug: "templates", type: "workflow-templates", label: "Plantillas de flujo", singular: "plantilla", newLabel: "Nueva plantilla", description: "Columnas y tipos de trabajo con los que nace un proyecto." },
  { slug: "methodologies", type: "methodologies", label: "Metodologías", singular: "metodología", newLabel: "Nueva metodología", description: "Qué documentos pide un proyecto de software, dónde viven en el repo y quién los escribe." },
  { slug: "roles", type: "roles", label: "Roles de agentes", singular: "rol", newLabel: "Nuevo rol", description: "Instrucciones, herramientas y automatización de cada especialista." },
  { slug: "components", type: "component-templates", label: "Piezas", singular: "pieza", newLabel: "Nueva pieza", description: "Con qué se arma un proyecto: APIs, webs, microservicios o microfrontends, cada una con su código base y comandos." },
  { slug: "rules", type: "agent-policies", label: "Reglas de agentes", singular: "reglas", newLabel: "", description: "Lo que reciben todos los agentes además del manual de su rol: reglas comunes, cómo preguntar, límites de costo y los textos que escriben en el tablero y en el repo." },
  { slug: "mcp", type: "mcp-servers", label: "Servidores MCP", singular: "servidor MCP", newLabel: "Nuevo servidor MCP", description: "Herramientas externas que los agentes pueden usar mientras trabajan." },
] as const satisfies readonly { slug: string; type: CatalogType; label: string; singular: string; newLabel: string; description: string }[];

export type CatalogSection = (typeof CATALOG_SECTIONS)[number];

export function sectionBySlug(slug: string): CatalogSection | undefined {
  return CATALOG_SECTIONS.find((s) => s.slug === slug);
}

/** Misma regla que la API: mayúsculas, números o guion bajo, empezando por letra. */
export const KEY_PATTERN = "[A-Z][A-Z0-9_]{1,39}";
