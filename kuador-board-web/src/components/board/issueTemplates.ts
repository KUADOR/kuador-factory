import type { MarkdownTemplate } from "@/components/ui/MarkdownEditor";
import type { IssueType } from "@/lib/types";

/**
 * Plantillas para empezar la descripción, como en Jira. En software traen secciones que leen los agentes: el PO toma
 * la historia y los criterios, el Arquitecto el contrato (un bloque ```json llega como JSON).
 */
const STORY = `## Contexto
Qué problema resuelve y para quién.

## Historia
Como **<rol>** quiero **<capacidad>** para **<beneficio>**.

## Criterios de aceptación
- [ ] Dado …, cuando …, entonces …
- [ ] Dado …, cuando …, entonces …

## Contrato esperado
### Petición
\`\`\`http
POST /api/usuarios
\`\`\`
\`\`\`json
{
  "nombre": "Ana Pérez",
  "correo": "ana@clinica.com",
  "rol": "RECEPCION"
}
\`\`\`

### Respuesta (201)
\`\`\`json
{
  "id": "7c9e…",
  "nombre": "Ana Pérez",
  "estado": "INVITADO"
}
\`\`\`

### Errores
| Código | Cuándo | Mensaje |
|---|---|---|
| 409 | El correo ya existe | «Ya hay un usuario con ese correo» |
| 422 | Falta un dato obligatorio | «Indica el nombre» |

## Fuera de alcance
- …
`;

const BUG = `## Qué pasa
Descripción corta del error.

## Pasos para reproducirlo
1. …
2. …

## Esperado
…

## Obtenido
…

## Evidencia
\`\`\`
Mensaje de error, log o respuesta
\`\`\`

## Entorno
| Dónde | Versión / navegador |
|---|---|
| Producción | … |
`;

const EPIC = `## Objetivo
Qué cambia para el negocio cuando esté lista.

## Alcance
- …

## Historias previstas
| # | Historia | Rol |
|---|---|---|
| 1 | … | Backend |
| 2 | … | Frontend |

## Fuera de alcance
- …

## Riesgos y dependencias
- …
`;

const TASK = `## Qué hacer
…

## Detalle técnico
\`\`\`
…
\`\`\`

## Terminado cuando
- [ ] …
`;

const GENERAL = `## Contexto
…

## Detalle
| Dato | Valor |
|---|---|
| … | … |

## Próximos pasos
- [ ] …
`;

export function issueTemplates(software: boolean, type: IssueType): MarkdownTemplate[] {
  if (!software) return [{ label: "Contexto, detalle y próximos pasos", text: GENERAL }];
  const all: Record<string, MarkdownTemplate> = {
    STORY: { label: "Historia con contrato JSON", text: STORY },
    BUG: { label: "Bug", text: BUG },
    EPIC: { label: "Épica", text: EPIC },
    TASK: { label: "Tarea técnica", text: TASK },
  };
  // La del tipo actual primero; las demás también sirven.
  return [all[type], ...Object.values(all).filter((t) => t !== all[type])].filter(Boolean);
}
