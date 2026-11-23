# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

- **Núcleo:** el equipo de Kuador S.A.S. (dueño y desarrolladores) que opera proyectos de software junto a un equipo de agentes de IA (PO, arquitecto, backend, frontend, DevOps, QA, release, marketing) sobre el mismo flujo de issues.
- **Desde el inicio también:** clientes, cada uno en su propio espacio de trabajo. Por defecto solo ven el avance; si un equipo quiere que un cliente apruebe algo, le da un rol y asigna ese rol a la puerta correspondiente (configuración, no código).
- **Situación:** uso diario, en escritorio, en sesiones largas de trabajo; revisar el tablero, refinar issues, leer y aprobar documentos, ver qué hicieron los agentes.

## Product Purpose

Kuador Go: una fábrica de trabajo para cualquier negocio. Cada empresa describe cómo se hace su trabajo (pasos, responsables y aprobaciones) y personas y agentes de IA lo sacan adelante en el mismo tablero; en software, el flujo es backlog → diseño → desarrollo → QA → release. Las personas deciden y aprueban; los agentes refinan, documentan y ejecutan. Éxito: un proyecto avanza de idea a release con los agentes haciendo el trabajo repetible y las personas en las puertas de aprobación, sin perder trazabilidad.

## Positioning

Personas y agentes son miembros del mismo equipo en el mismo tablero: los agentes se asignan, comentan, escriben specs versionadas y devuelven el trabajo; las aprobaciones son siempre humanas. La metodología (SDD, ODD o propia), las plantillas de flujo y los roles son datos configurables por espacio, y los documentos se sincronizan con el repositorio (GitHub vía Pull Requests).

## Operating Context

- Tableros de cualquier negocio en el mismo espacio: cada uno enciende lo que usa (documentos y manual de trabajo, repositorio de código, reparto en tareas) y define los datos propios de sus tarjetas.
- Los agentes trabajan en el tablero (con la clave de IA de la empresa) o en la PC de cada persona (su cliente de IA por MCP, o el runner), y pueden usar los sistemas de la empresa conectados por MCP.
- Documentos en Markdown con versiones; conflictos de edición resueltos por versión base, nunca sobrescritos en silencio.

## Capabilities and Constraints

- Next.js 16 (App Router) + React 19, TypeScript, pnpm vía corepack; la API Spring Boot vive aparte y el navegador nunca la llama directo (server actions y server components con token de sesión en cookie httpOnly).
- Componentes: PrimeReact 11 con tema propio Kuador a partir de tokens de diseño. Tema oscuro por defecto con opción clara.
- Español (`es-EC`) en toda la interfaz.
- Catálogo por espacio: plantillas de flujo, metodologías, roles de agentes, servidores MCP; editable solo por personas.
- Alta de espacios abierta (SaaS) desde el login, con el correo verificado por código.
- Acceso por persona: dueño, administrador, «Puede editar» o «Solo lectura». Quien solo mira ve el tablero, los documentos y la actividad sin controles de edición, y aprueba o rechaza solo en las puertas de su rol. Los clientes nacen en solo lectura.
- Etapas: un issue que llega a una etapa queda sin asignar y lo toma cualquiera con el rol responsable; las puertas las cierra una persona con el rol aprobador. Valen en cualquier tablero, según su configuración.

## Brand Commitments

- Nombre del producto: Kuador Go (empresa Kuador S.A.S., Quito). Identidad de marca de Kuador: noche técnica con un solo sol ecuatorial (amarillo `#ffb81c`), definida en `landing/kuador-app/DESIGN.md`.
- El tablero debe sentirse profesional, a la altura de Jira, Linear y las plantillas oficiales de PrimeReact; el usuario rechazó explícitamente que «parezca hecho por IA».
- Densidad compacta en pantallas de trabajo.

## Evidence on Hand

- Catálogo de fábrica real: `kuador-board-api/src/main/resources/catalog/defaults.yaml` (plantillas Software/Ventas/Tareas, metodologías SDD/ODD/Libre, ocho roles de agentes, Context7).
- **Ausente, no inventar:** clientes nombrados, métricas de uso o resultados.

## Product Principles

1. **Las personas aprueban, los agentes ejecutan.** Toda puerta de aprobación es humana y se ve como tal.
2. **Trazable siempre.** Quién hizo qué (persona o agente), cuándo y con qué versión del documento.
3. **Configurable, no fijo.** Flujos, metodologías y roles salen del catálogo del espacio.
4. **Familiar antes que original.** Patrones que un usuario de Jira o Linear reconoce sin pensar.

## Accessibility & Inclusion

WCAG AA: contraste AA en ambos temas, foco visible, navegación completa por teclado (incluido arrastrar tarjetas con teclado), `prefers-reduced-motion` respetado.
