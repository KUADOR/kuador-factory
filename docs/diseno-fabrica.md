# Diseño de la fábrica de software Kuador

Decisiones tomadas el 2026-11-10. Este documento es la referencia para construir la orquestación de agentes.

## Idea central

El tablero es **un equipo**. Cada rol (PO, arquitecto, dev, QA, release) lo puede cubrir **un agente,
una persona o ambos**. Sirve igual si una sola persona trabaja con un equipo de agentes que si el equipo
es mixto. El trabajo pasa de un rol a otro **automáticamente**, con **puertas** donde solo una persona
decide.

| | Tablero | Claude Code (laptop de cada dev) |
|---|---|---|
| Responde | qué, por qué, quién, en qué estado | cómo |
| Contiene | issues, flujo, roles, metodología, constitución, specs, historial, permisos | el repo, las herramientas y la suscripción del dev |

Un agente del tablero no ejecuta: es un **rol con identidad**. Asignarle un issue expresa intención,
todo lo que hace queda firmado en el historial, su token tiene permisos limitados y su "manual" (rol y
reglas de la metodología) se mantiene en un solo lugar.

## Ejecución: Claude Code + servidor MCP del tablero

- Los agentes trabajan en **Claude Code**, en la máquina de cada dev y **con la suscripción de ese
  dev**. Ningún servidor usa la suscripción de nadie (ver la memoria
  `agents-run-on-local-subscription`).
- El tablero expone un **servidor MCP** (leer pendientes y contexto, comentar, escribir documentos,
  entregar). Claude Code se conecta con un token de agente.
- **Modos** (decisión: ambos):
  - **Bajo demanda (por defecto):** en Claude Code, "trabaja mis pendientes", y atiende uno por uno los
    issues que esperan a los agentes de ese dev.
  - **Automático en su máquina:** el dev deja Claude Code revisando la cola cada cierto tiempo. Consume
    los límites de su plan.
- Cada trabajo de un agente tiene un **responsable humano**: la persona que lo puso en marcha. En una
  cadena (PO → dev → QA) se hereda. Cada dev solo atiende la cola de la que es responsable.
- El adaptador con API key queda como alternativa apagada.

## Flujo por defecto (proyecto de Software)

```
Backlog → Refinamiento → Aprobación → Diseño → Desarrollo → QA → Revisión PR → develop → Release → main
          🤖 PO          👤 persona    🤖 Arq.  🤖 dev        🤖 QA  👤 o auto              🤖 release + 👤
```

| Etapa | Responsable | Salida |
|---|---|---|
| Refinamiento | Agente PO | Al entregar → Aprobación |
| **Aprobación** | **Persona** (puerta) | Aprueba → Diseño · Rechaza → Refinamiento con comentario |
| Diseño *(opcional; SDD)* | Agente Arquitecto | `plan.md` validado contra la constitución. Decide Backend, Frontend o subtareas → Desarrollo |
| Desarrollo | Agente Backend / Frontend | Rama `feature/KUA-8-…` con pruebas → QA |
| QA | Agente QA | OK → el dev abre el PR a `develop` → Revisión PR · Falla → Desarrollo con evidencia |
| **Revisión PR** | **Persona por defecto**; merge automático configurable (QA OK + CI verde) | Merge a `develop` |
| **Release** | Agente Release prepara el PR `develop → main` y las notas; **persona aprueba** | Merge a `main` |

Decisiones: PR a `develop` **configurable, humano por defecto**. Paso a `main` con **Agente Release y
aprobación humana**. Etapa de Diseño del Arquitecto **sí, configurable**.

## Reglas que impone el sistema (no el prompt)

1. **Roles por etapa:** cada columna tiene un rol responsable; el rol lo cubre un agente, una persona o
   ambos.
2. **Traspaso:** cuando el responsable entrega, el issue pasa a la siguiente etapa y se asigna a su
   responsable.
3. **Puertas:** las etapas marcadas solo las cierra una persona. El token de un agente recibe 403.
4. **Freno de bucles:** más de 3 vueltas QA ↔ Desarrollo → se asigna a una persona.
5. **Git protegido:** rama por issue y PRs con CI obligatorio. La protección de ramas de GitHub impide
   que un agente haga merge a `main` (o a `develop` si el proyecto exige revisión humana).

## Proyecto nuevo con sus bases (desde GitHub)

Al crear un proyecto de Software se eligen la **plantilla de stack**, la **metodología** y la
**organización de repos**. El tablero entonces:

1. **Crea los repos desde plantillas de Kuador.** El código base es determinista y probado; no lo
   genera la IA. Plantillas iniciales:
   - **API Spring Boot hexagonal:** Java 25, Modulith, Flyway, pruebas de arquitectura, Dockerfile,
     CI.
   - **Web Next.js:** TypeScript strict, Tailwind, pnpm, ESLint, Vitest, CI.
   - **Angular.**
   - **Proyecto vacío con bases:** solo la metodología, el flujo de git, CI mínimo y los archivos para
     agentes.
2. **Prepara git:** crea `develop`, protege `main` (PR con aprobación humana) y protege `develop` (PR
   con CI, revisión humana o automática según el proyecto).
3. **Agrega la base de la metodología:** constitución con el nombre del proyecto, `AGENTS.md`,
   `.specify/` u `odd/`.
4. **Deja el repo listo para los agentes:** `CLAUDE.md`, subagentes `.claude/agents/` generados desde
   los roles del tablero y `.mcp.json` para que Claude Code se conecte solo al tablero.
5. **Vincula e importa** los documentos.

Decisiones:
- **Credencial:** una **GitHub App de Kuador** instalada en la organización, con permisos acotados y
  tokens de corta duración. Ningún token de administrador permanente.
- **Repos:** **a elección por proyecto**, uno por pieza (`-api`, `-web`) o monorepo. Implica que un
  proyecto pueda vincular **varios repositorios** (hoy solo uno), y que cada documento sepa a cuál
  pertenece.

## Todo configurable (el tablero como producto)

Principio: **el tablero no conoce stacks, herramientas ni organizaciones; conoce configuración.**
Cambiar de Spring a Quarkus, agregar Context7 o crear el repo en la organización de un cliente no
requiere tocar código del tablero.

**Espacios de trabajo (multi-empresa).** Todo pertenece a un espacio de trabajo: miembros, proyectos,
plantillas, conexiones, roles, metodologías y catálogo MCP. Hoy existe solo **Kuador**, y lo existente
migra ahí. Un cliente sería otro espacio, sin ver nada del resto.

**Catálogos configurables por espacio**, con lo de hoy como datos de fábrica:

| Catálogo | Contenido |
|---|---|
| Plantillas de flujo | Columnas, categorías, rol responsable por etapa, puertas humanas, traspasos |
| Metodologías | Documentos que pide (tipo, ámbito, ruta en el repo, plantilla de contenido) y reglas para agentes. SDD, ODD y Libre vienen de fábrica |
| Roles de agente | Instrucciones, herramientas permitidas, etapa donde trabaja |
| Servidores MCP | Context7, GitHub, Postman… Se asignan a plantillas y proyectos. Sus claves **nunca** se guardan en el repo: el `.mcp.json` referencia variables de entorno de cada dev |
| Conexiones GitHub | Instalaciones de la GitHub App (Kuador, clientes). Al crear un proyecto se elige dónde, o se vincula un repo existente |
| Plantillas de stack | **Definidas en el tablero** (decisión): repo de código base fijado en una versión, variables a reemplazar, comandos (compilar, probar, levantar), roles sugeridos, servidores MCP y verificaciones de CI obligatorias. Configuración versionada: cada proyecto sabe con qué plantilla y qué versión nació |

## GitHub: MCP para los agentes, GitHub App para el tablero

Decidido el 2026-11-10:

- **Agentes → servidor MCP oficial de GitHub** (`github/github-mcp-server`, en el catálogo como `GITHUB`
  remoto con OAuth y `GITHUB_LOCAL` en Docker con token propio). Cada agente lee el repo, crea ramas
  y abre PRs con la cuenta de quien lo corre; el tablero no guarda credenciales para eso.
- **Tablero → GitHub App de Kuador** (fase 3): reemplaza el token por proyecto. La organización la
  instala una vez y elige los repos; el tablero la usa para sincronizar documentos, ver el estado de
  sus PRs, crear repos desde plantillas y recibir webhooks.
- Mientras tanto, el vínculo por token de la configuración del proyecto sigue funcionando.

## Fases de construcción

0. **Base configurable:** espacios de trabajo y catálogos (flujos, metodologías, roles, MCP) como
   datos, migrando lo de hoy a datos de fábrica del espacio Kuador.
   ✅ Hecha (2026-11-10): aislamiento por espacio con `@TenantId` (probado contra Postgres real),
   catálogo de fábrica en `catalog/defaults.yaml` editable por espacio en la web, e interfaz
   rehecha con PrimeReact 11 y el tema Kuador (oscuro por defecto, claro opcional).
   Decidido después: alta abierta (SaaS) desde el login con código por correo; un cliente solo ve
   el avance salvo que se le dé un rol aprobador en una puerta.
   Acceso por persona (`member.access`): `EDIT` o `VIEW`. La API lo hace cumplir (autoridad
   `ROLE_EDITOR`: toda escritura la exige, salvo cerrar sesión, cambiar la propia clave y revisar una
   puerta, donde el servicio comprueba el rol aprobador). Un cliente (`CLIENTE`) nace en `VIEW`; los
   agentes siempre editan; nadie cambia su propio acceso.

1. **Etapas con roles, traspasos y puertas:** modelo, reglas, configuración por proyecto y la nueva
   plantilla de Software.
   ✅ Hecha (2026-11-10). Decisiones: un issue que llega a una etapa queda **sin asignar** y lo toma
   cualquiera con uno de sus roles; las **puertas** existen en cualquier tablero según su configuración
   y solo las cierra una persona con el rol aprobador (un agente recibe 403); el cliente solo ve
   salvo que se le dé ese rol. Acciones: tomar, entregar (a la siguiente etapa que permita el
   flujo) y revisar (aprobar o rechazar con motivo). Freno de bucles configurable (3 por defecto):
   pasado el límite de vueltas atrás, el issue va a quien lo reportó. Plantilla de fábrica
   «Fábrica de software» (`SOFTWARE_FACTORY`, catálogo v3).
2. **Servidor MCP del tablero** + comando "trabaja mis pendientes" + subagentes de Claude Code
   generados desde los roles del tablero.
   ✅ Hecha (2026-11-10). Servidor MCP propio en `/mcp` (streamable HTTP, JSON sin SSE, sin estado;
   rechaza peticiones con `Origin`). Se entra con la **conexión de Claude Code** de una persona (token
   `RUNNER`, `kfr_…`): solo abre `/mcp` y el kit, y actúa como los agentes del espacio (`agente` = clave
   del rol), todo firmado por el agente. **Supervisión:** la persona que pone a trabajar a los agentes
   (asigna a un agente, o lleva el issue a una etapa que cubren agentes, p. ej. al aprobar una puerta)
   lo supervisa; los agentes lo heredan por la cadena y solo los agentes de esa persona lo atienden
   (cada suscripción sirve solo a su dueño). Herramientas: `pendientes`, `ver_issue`,
   `instrucciones_rol`, `tomar`, `comentar`, `estimar`, `entregar`, `devolver`, `consultar`, `leer_documento`,
   `escribir_documento`. **Kit** (página Agentes locales o `GET /api/agent-kit.zip`): `.mcp.json` (tablero +
   servidores del catálogo elegidos, todo con variables de entorno), un subagente por rol que pide su
   manual vigente con `instrucciones_rol`, y el comando `/pendientes`. El trabajo en Claude Code queda
   como ejecución `claude-code` (el tablero muestra al agente trabajando). Los clientes también comentan.
   Probada con Claude Code real (2026-11-10): `/pendientes` en modo `-p`, el subagente del PO tomó un
   issue SDD, escribió la spec (v1→v3), consultó dos veces a la persona y al final estimó y entregó a
   Aprobación (~75 s y 5 turnos por vuelta).
   **Abierto a cualquier cliente de IA (decisión del usuario):** el kit se elige por cliente (Claude
   Code, Codex con `.codex/config.toml` + `AGENTS.md`, u otro cliente MCP) y todo lleva el nombre del
   espacio (servidor `<slug>`, `TABLERO_<SLUG>_TOKEN`, subagentes `<slug>-<rol>`), nunca «Kuador»:
   cada empresa tiene su propio tablero. La ejecución registra el cliente (`local:<cliente>`, del
   User-Agent). Codex no se probó en vivo (no está instalado); su formato sale de la documentación oficial.
3. **Proyecto nuevo con sus bases:** GitHub App, repos de plantilla, flujo de git protegido, archivos
   para agentes y varios repos por proyecto.
   🟡 Primera parte hecha (2026-11-10), decisiones del usuario: organización **KUADOR**, app registrada
   **con un clic** (manifiesto de GitHub) y **proyecto vacío con bases** (plantillas de stack después, como
   entradas de catálogo). Módulo `github`: la app es una sola para la plataforma (fila única, secretos
   cifrados) y la registra la dueña del espacio que opera la plataforma (`board.platform.workspace`);
   cada espacio **conecta organizaciones** instalándola, y al volver se verifica con OAuth que quien
   instaló administra esa instalación. Tokens de instalación de 1 h, reutilizados. Vínculo nuevo
   `GITHUB_APP` (sin token personal). **Crear repositorio con sus bases:** commit inicial con README
   (flujo de ramas), AGENTS.md (el de la metodología lleva además la sección del tablero), .gitignore,
   CI mínimo (job `ci`) y los documentos del proyecto; `develop` predeterminada; protección de `main`
   (PR + 1 aprobación + CI) y `develop` (PR + CI). En el plan gratuito, GitHub no protege ramas de repos
   privados: el tablero lo intenta y lo avisa. Webhook creado pero apagado hasta tener URL pública.
   **Piezas (decisión del usuario):** el stack es **por proyecto** y un proyecto se arma con **varias
   piezas** (API .NET + web Angular; Spring + React; microservicios; microfrontends + shell). El catálogo
   guarda **plantillas de pieza** (`COMPONENT_TEMPLATE`, catálogo v4: repo plantilla en versión fija,
   variables `{{X}}`, comandos, CI exigido, roles y MCP); de fábrica vienen 8 sin repo, cada empresa
   apunta las suyas. Cada proyecto elige **monorepo** (piezas en carpetas) o **un repo por pieza** (los
   documentos viven en el repo del proyecto). Sumar una pieza después: carpeta por PR a `develop`, o repo
   propio con sus bases. Las piezas guardan con qué plantilla y versión nacieron; los agentes las ven en
   `ver_issue` (stack, repo, carpeta, comandos). Falta: webhooks.
4. **PO y Arquitecto vía MCP:** se reutilizan las reglas de hoy (comentar, spec, plan, devolver).
5. **Dev y QA:** ramas, pruebas, evidencias y apertura del PR a `develop`.
6. **Release** (PR `develop → main` + notas), merge automático configurable, guía de protección de
   ramas y modo automático.
