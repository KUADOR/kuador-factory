# Kuador Factory

Fábrica de software de Kuador: un **tablero tipo Jira** donde personas y **agentes especialistas**
(PO, arquitecto, backend Spring Boot, frontend React/Angular, DevOps, QA, marketing) trabajan en el
mismo flujo de issues.

| Carpeta | Qué es | Stack |
|---|---|---|
| `kuador-board-api` | API del tablero | Java 25 · Spring Boot 4.1 · Spring Modulith (hexagonal por módulo) · PostgreSQL 18 · Flyway |
| `kuador-board-web` | Interfaz del tablero | Next.js 16 · React 19 · TypeScript strict · PrimeReact 11 (tema Kuador) · Tailwind v4 · dnd-kit · pnpm |

## Levantar en local

```bash
# 1. Base de datos (una sola vez)
brew services start postgresql@18
psql -d postgres -c "CREATE ROLE kuador LOGIN PASSWORD 'kuador'"
createdb -O kuador kuador_board

# 2. API → http://127.0.0.1:8091  (Swagger: /swagger-ui.html)
cd kuador-board-api
cp .env.example .env   # primera cuenta, BOARD_SECRETS_KEY y BOARD_SYNC_ALLOWED_ROOTS
JAVA_HOME=~/.sdkman/candidates/java/25.0.2.fx-zulu ./mvnw spring-boot:run

# 3. Web → http://localhost:3600
cd kuador-board-web
cp .env.example .env.local   # PRIMEUI_LICENSE: clave PrimeUI (sin ella sale un aviso de licencia)
corepack pnpm install && corepack pnpm dev
```

Pruebas de la API: `./mvnw test` (dominio, casos de uso y límites entre módulos) y `./mvnw verify`
(además, aislamiento entre espacios contra un Postgres real con Testcontainers; requiere Docker).

## Espacios de trabajo y catálogo

- Todo vive dentro de un **espacio de trabajo** (Kuador, o el de cada cliente). Cada entidad lleva
  `workspace_id` y Hibernate filtra por el espacio de quien hace la petición (`@TenantId`); sin
  espacio, una consulta no devuelve nada. Dos espacios pueden tener el mismo proyecto `KUA`.
- Al crear un espacio se copia el **catálogo de fábrica** (`catalog/defaults.yaml`), se crea la
  persona dueña con acceso y un agente por cada rol. El primero se crea al arrancar con las variables
  `BOARD_BOOTSTRAP_*`.
- **Alta abierta (SaaS)** desde el login: *Crear espacio de trabajo* → correo → código de 6 dígitos
  (vence en 10 min, 5 intentos, un código por minuto y 5 por hora) → empresa (nombre, descripción,
  logo y **color base**) → persona dueña. Sin correo verificado no se crea nada. El SMTP se configura
  en el `.env` de la API con `spring.mail.*`; en local, `BOARD_MAIL_LOG_CODES=true` escribe el código
  en el log de la API (nunca en producción).
- **Marca del espacio** (*Marca y empresa*): el color base pinta botones principales, selección y foco
  para todo el equipo; de ese color se derivan los tonos y el texto legible en ambos temas.
- El catálogo de fábrica tiene **versión** (`catalog/defaults.yaml`, `version` y `releases`): al
  arrancar, cada espacio recibe una sola vez lo que se agregó después de su versión, sin tocar lo que
  el equipo editó ni revivir lo que borró. Así llegaron los servidores MCP de GitHub.
- El **catálogo** es configurable por espacio en la web (*Catálogo*): plantillas de flujo,
  metodologías (documentos, rutas en el repo, quién los escribe), roles de agentes (instrucciones,
  herramientas, automatización) y servidores MCP. Lo de fábrica se edita y se restaura; lo propio se
  crea y se borra si nadie lo usa (409 si está en uso). Solo las personas lo editan.

## Interfaz

- PrimeReact 11 (`@primereact/ui`) con el preset `src/theme/kuador-preset.ts`: un solo preset para
  oscuro (por defecto) y claro mediante `light-dark()`; el tema elegido se guarda en la cookie
  `kuador_theme`. Las utilidades de Tailwind leen los mismos tokens (`src/app/globals.css`).
- Componentes propios sobre PrimeReact en `src/components/ui` (botón, campos, panel lateral,
  diálogo, menú, pestañas, avisos): las pantallas usan esa capa, no PrimeReact directo.
- Contexto de diseño para Impeccable en `PRODUCT.md` y `DESIGN.md` de `kuador-board-web`.

## Autenticación

| Quién | Cómo entra | Token |
|---|---|---|
| Persona | Correo + contraseña en `/login` (bcrypt, mínimo 10 caracteres) | Sesión `kfs_…`, 7 días, en cookie `httpOnly` del servidor de Next |
| Agente | Token de servicio creado en **Equipo** (se muestra una sola vez) | `kfa_…`, vigente hasta revocarlo |

- Todo va en `Authorization: Bearer <token>`. La base solo guarda el SHA-256 del token.
- La primera cuenta se crea al arrancar si no existe ninguna, con `BOARD_BOOTSTRAP_ADMIN_EMAIL` y
  `BOARD_BOOTSTRAP_ADMIN_PASSWORD`. A partir de ahí, se da acceso a otras personas desde **Equipo**.
- El autor de issues y comentarios es quien está autenticado; el body no puede suplantarlo.
- Solo las personas crean proyectos y miembros, dan accesos y emiten o revocan tokens. Un agente
  recibe 403 si lo intenta.
- Tras 5 intentos fallidos, el login de ese correo se bloquea 15 minutos (429). Cambiar la
  contraseña cierra las demás sesiones.

Probar la API como agente:

```bash
curl -H "Authorization: Bearer kfa_..." http://127.0.0.1:8091/api/auth/me
```

## Agentes

Un agente es un miembro del tablero. El módulo `agents` lo despierta cuando **una persona** le
asigna un issue (evento `IssueAssigned`). Por ahora trabaja el **Agente PO** (rol `PRODUCT_OWNER`):

1. **Arma el contexto** desde el código:
   - su rol (la descripción del miembro) y la metodología con sus reglas;
   - los documentos del proyecto (constitución, producto, arquitectura, glosario, reglas para
     agentes); un documento de más de 30.000 caracteres se recorta y se le avisa al modelo;
   - la épica, el issue, los últimos 20 comentarios y la spec si ya existe.
2. **Llama a Claude una sola vez**: `claude-opus-5-5`, effort `medium`, salida estructurada
   validada contra `PoProposal`, y `fallbacks: "default"` por si el modelo declina por sus
   clasificadores de seguridad.
3. **Actúa** según reglas del código, no del modelo:
   - comenta la propuesta: historia, criterios Dado/Cuando/Entonces, preguntas para decidir y
     fuera de alcance;
   - escribe la **spec** (SDD) o el **seguimiento** (ODD, solo si es sustancial) como una versión
     nueva;
   - estima **solo si no había estimación**;
   - **devuelve el issue a quien se lo asignó**. No lo mueve de columna: aprobar es de una persona.

Salvaguardas:
- Si lo asigna otro agente, no actúa (se evitan los bucles entre agentes).
- La llamada al modelo va fuera de transacciones. Si falla, la ejecución queda `FAILED` y **no se
  reintenta sola**; se reintenta volviendo a asignar el issue.
- Un evento reentregado no dispara dos ejecuciones.
- Si la API se reinicia a mitad de una ejecución, al arrancar se marca como interrumpida.
- Cada ejecución queda en `agent_run`, con modelo, tokens, resumen o error. Se ve en **Equipo** y
  en el **Historial** del issue.

Activar: `ANTHROPIC_API_KEY` en el `.env` de la API. Sin clave, cada asignación se registra como
`SKIPPED`, con el motivo. **Cada asignación al Agente PO consume tokens.** Configurable:
`BOARD_AGENTS_MODEL`, `BOARD_AGENTS_EFFORT`, `BOARD_AGENTS_REFUSAL_FALLBACK`.

## Eventos de dominio

El módulo `issue` publica estos eventos (paquete `ec.kuador.board.issue`). Spring Modulith los guarda
en `event_publication` dentro de la misma transacción del cambio: si un listener falla, el evento
queda pendiente y se reentrega al reiniciar. Todos llevan `eventId`, `issueKey`, `projectKey`,
`actorId` (persona o agente) y `occurredAt`.

| Evento | Cuándo | Datos propios |
|---|---|---|
| `IssueCreated` | Se crea un issue | `title`, `type`, `status`, `category` |
| `IssueUpdated` | Cambian título, descripción, tipo, prioridad, puntos, épica o etiquetas | `changes[]` (`field`, `from`, `to`) |
| `IssueMoved` | Cambia de columna; reordenar dentro de la misma columna no lo emite | `fromStatus`, `toStatus`, `fromCategory`, `toCategory` |
| `IssueAssigned` | Cambia el responsable, por cualquier vía (crear, editar o asignar) | `fromAssigneeId`, `toAssigneeId` |
| `IssueCommented` | Alguien comenta | `commentId`, `excerpt` (280 caracteres) |
| `IssueDeleted` | Se borra un issue | `title` |
| `DocumentSaved` (módulo `docs`) | Se guarda una versión de un documento (v1 = creado) | `documentId`, `kind`, `path`, `version` |

Para escucharlos desde otro módulo, usa `@ApplicationModuleListener void on(IssueEvent e)` y descarta
los `eventId` ya procesados, porque una reentrega puede repetir un evento. Así lo hace el módulo
`activity`, que guarda el historial y lo expone en `/api/issues/{key}/activity` y
`/api/projects/{key}/activity`. El historial se conserva aunque el issue se borre.

## Metodología y documentos (solo proyectos de Software)

En *Configurar* se elige la metodología; en *Docs* se crean los documentos desde plantillas. Cada
guardado es una versión nueva (autor, nota y fecha) y cualquier versión se puede ver o restaurar.

| Metodología | Del proyecto | Por issue |
|---|---|---|
| **SDD** (Spec Kit) | **Constitución** `.specify/memory/constitution.md`, producto, arquitectura, glosario, reglas para agentes | **Spec**, plan y tareas en `specs/NNN-nombre/` |
| **ODD** | Producto, arquitectura, glosario, reglas para agentes | Seguimiento `odd/tasks/clave-nombre.md`, solo si la tarea es sustancial |
| **Libre** | **Reglas para agentes** `AGENTS.md`, producto, arquitectura, glosario | Notas `docs/issues/…` |

- La ruta de cada documento coincide con la del repositorio, para la sincronización de la etapa C.
- Personas y agentes pueden escribir documentos. Solo las personas cambian la metodología.
- Al guardar se envía `baseVersion`: si otra persona o un agente guardó antes, la API responde 409
  y no se pisa nada.
- Las metodologías son entradas del catálogo (`/api/catalog/methodologies`): sus documentos, rutas,
  plantillas y reglas para agentes se pueden ajustar o crear nuevas por espacio.
- El Markdown se muestra sin interpretar HTML: un documento no puede inyectar scripts.

## Sincronización con GitHub (modo equipo)

Cada dev sigue trabajando con git como siempre. El tablero ve el repo **desde la rama principal**,
que es lo que el equipo ya fusionó:

- **Importar**: lo fusionado en `main`, venga de quien venga, entra al tablero como versión nueva.
- **Exportar**: abre un **Pull Request** con todos los documentos en un solo commit (rama
  `kuador/docs-…`). Si ya hay un PR del tablero abierto, agrega un commit a ese mismo PR.
- **Fusionar el PR** en GitHub marca sus documentos como sincronizados. El tablero lo detecta al
  abrir *Sincronizar*. Si después editaste en el tablero, ese cambio queda pendiente y no se pierde.
- **PR cerrado sin fusionar**: sus documentos vuelven a "pendiente de exportar".
- **Conflicto** (cambió en el tablero y en `main`): se compara y se elige. Elegir el tablero abre
  un PR, que se revisa antes de fusionar.

Vincular: *Configurar → Repositorio → GitHub*, con `owner/repo`, la rama (vacío = rama por defecto)
y un **fine-grained token** con acceso solo a ese repo y permisos *Contents* y *Pull requests* en
lectura y escritura. El token se guarda **cifrado con AES-256-GCM** usando `BOARD_SECRETS_KEY`; no
se devuelve ni se registra en logs. La API solo habla con `BOARD_GITHUB_API_URL`
(`https://api.github.com`), que fija el servidor y no el usuario.

Pendiente: un **webhook** para importar en cuanto se fusiona algo. Necesita que el tablero tenga
una URL pública; hasta entonces, el estado se actualiza al abrir *Sincronizar*.

## Sincronización con una carpeta local (solo pruebas personales)

En *Configurar → Repositorio* se vincula la carpeta local del repo; en *Docs → Sincronizar* se ve el
estado de cada documento. El tablero **escribe archivos pero no hace commits**: revisa con
`git diff` y haz commit tú.

| Estado | Significa | Acción |
|---|---|---|
| Sincronizado | Igual en ambos lados | — |
| Cambió en el tablero / Solo en el tablero | Editado aquí después de sincronizar / nunca exportado | Exportar |
| Cambió en el repo | Editado en el repo (tú, Spec Kit, un editor) | Importar como versión nueva |
| **Conflicto** | Cambió en ambos lados | Comparar (diff) y elegir un lado |
| Borrado en el repo | Se sincronizó y luego se borró el archivo | Exportar para recrearlo, o dejarlo así |

- Sincroniza en **tres vías**: guarda la versión del tablero y el SHA-256 del archivo de la última
  sincronización, para saber qué lado cambió.
- *Sincronizar lo seguro* exporta e importa lo que cambió de un solo lado. **Los conflictos nunca
  se resuelven solos.**
- **Importar lo existente**: detecta `.specify/memory/constitution.md`, `PRODUCT.md`, `AGENTS.md`,
  `docs/architecture.md`, `docs/glossary.md`, `specs/*/{spec,plan,tasks}.md`, `odd/tasks/*.md` y
  `docs/issues/*.md`. Una carpeta de spec se importa a un issue existente o a uno nuevo, conservando
  su ruta real (p. ej. `specs/001-lead-capture/`).
- **Seguridad** (la API escribe en el disco):
  - solo carpetas dentro de `BOARD_SYNC_ALLOWED_ROOTS`; si la variable está vacía, la sincronización
    está desactivada;
  - solo archivos `.md` de hasta 1 MB en UTF-8;
  - se rechazan `..`, rutas absolutas y enlaces simbólicos, y toda ruta se resuelve dentro de la
    raíz real;
  - escritura atómica;
  - solo las personas sincronizan.
- Hexagonal: `RepositoryFiles` es un puerto; la carpeta local es un adaptador y GitHub será otro.

## Modelo

- **Proyecto** con clave (`KUA`), nacido de una **plantilla de flujo** del catálogo (que define si es
  de software o general, sus columnas y tipos de issue); sus issues se numeran `KUA-1`, `KUA-2`…
  Plantillas de fábrica:

  | Plantilla | Columnas iniciales | Tipos de issue |
  |---|---|---|
  | `SOFTWARE` | Backlog → Por hacer → En progreso → En QA → Hecho | Épica, Historia, Tarea, Bug |
  | `SALES` (general) | Prospecto → Contactado → Propuesta enviada → Negociación → Ganado / Perdido | Oportunidad y Campaña (`TASK`/`EPIC`) |
  | `TASKS` (general) | Pendiente → Haciendo → Listo | Tarea y Grupo (`TASK`/`EPIC`) |

- **Reglas de transición** (*Configuración → Reglas de transición*): desde qué columna se puede mover
  un issue a cuál, como en Jira. La API las aplica a personas y agentes; en el tablero, las columnas no
  permitidas se atenúan al arrastrar. Las columnas también se agregan, renombran, reordenan y quitan
  desde el propio tablero.
- **Flujo configurable** en *Configurar* (⚙ en el tablero): renombrar, reordenar y agregar columnas
  (máx. 12). Cada columna tiene una **clave estable** (no cambia al renombrar) y una **categoría**
  (`TODO`, `IN_PROGRESS`, `DONE`) que permite a los agentes entender cualquier flujo. Una columna
  con issues no se puede quitar. Solo las personas editan el flujo.
- **Issue**: tipo, prioridad, puntos, etiquetas, padre y responsable; `status` es la clave de su columna.
- **Miembro**: `HUMAN` o `AGENT`. Un agente es un miembro más: se le asignan issues y comenta.
  Su `description` será la base de su prompt.

## API

| Método | Ruta | Uso |
|---|---|---|
| GET · PUT | `/api/workspace` | Espacio actual / nombre, descripción, `brandColor` y `logo` (data URL; solo personas) |
| POST | `/api/signup/code` · `/api/signup/verify` · `/api/signup/workspace` | Alta pública: pedir código, verificarlo (`signupToken`), crear el espacio |
| GET | `/api/signup/slug?name=` | Identificador libre sugerido para un nombre |
| POST | `/mcp` | Servidor MCP del tablero (JSON-RPC). Solo con la conexión de agentes locales de una persona (`kfr_…`) |
| GET · POST · DELETE | `/api/auth/runner-tokens` | Conexiones de agentes locales de quien pide (el secreto se ve una vez) |
| GET | `/api/agent-kit?client=claude-code\|codex\|mcp&mcp=CONTEXT7` · `/api/agent-kit.zip` | Kit para el repo en el formato del cliente de IA, con el nombre del espacio |
| POST | `/api/issues/{key}/supervise` | Quien llama pasa a supervisar el issue (sus agentes lo atienden) |
| GET | `/api/github` | App de la plataforma y organizaciones de GitHub conectadas al espacio |
| POST | `/api/github/app/manifest` · `/api/github/app` | Registrar la GitHub App con el manifiesto (una vez, quien opera la plataforma) |
| POST | `/api/github/installations/start` · `/api/github/installations` · DELETE `/api/github/installations/{id}` | Conectar o quitar una organización (verificado con OAuth) |
| POST | `/api/projects/{key}/repository/new` | Crear el repo en una organización conectada, con sus bases, y vincularlo (`GITHUB_APP`) |
| PUT | `/api/members/{id}/access` | Nivel de acceso de una persona `{access: EDIT\|VIEW}` (solo personas que editan; nadie cambia el suyo; siempre queda alguien que edita) |
| PUT | `/api/projects/{key}/stages` | Etapas `{stages: [{column, roles, gate, approvers, rejectTo}], maxRework}` (solo personas) |
| POST | `/api/issues/{key}/take` · `/deliver` · `/review` | Tomar de la etapa / entregar `{comment?}` / cerrar una puerta `{approve, comment}` (solo personas aprobadoras) |
| PUT | `/api/projects/{key}` · `/api/projects/{key}/transitions` | Nombre y descripción / reglas `{restricted, allowed: [{from, to}]}` (solo personas) |
| GET · PUT · DELETE | `/api/catalog/{workflow-templates\|methodologies\|roles\|mcp-servers}[/{key}]` | Catálogo del espacio; `PUT` crea o actualiza con `version` (solo personas) |
| POST | `/api/catalog/{tipo}/{key}/restore` | Volver a los valores de fábrica |
| GET/POST | `/api/projects` | Listar / crear proyectos `{key, name, description?, templateKey}` |
| GET | `/api/projects/{key}` | Detalle: `kind`, `templateKey`, `methodology`, `columns`, `issueTypes` |
| PUT | `/api/projects/{key}/columns` | Reemplaza el flujo (`[{key\|null, name, category}]`, en orden) |
| GET/POST | `/api/projects/{key}/issues?q=&assigneeId=&type=` | Tablero / crear issue |
| GET/PUT/DELETE | `/api/issues/{key}` | Detalle / editar (todos los campos) / borrar |
| PUT | `/api/issues/{key}/assignee` | Asignar a una persona o agente |
| POST | `/api/issues/{key}/move` | `{status, index}`: mover de columna y reordenar |
| GET/POST | `/api/issues/{key}/comments` | Comentarios |
| GET/POST | `/api/members` | Equipo (personas y agentes) |
| POST | `/api/auth/login` · `/api/auth/logout` | Abrir / cerrar sesión |
| GET | `/api/auth/me` | Quién soy (persona o agente) |
| PUT | `/api/auth/password` | Cambiar la propia contraseña |
| GET · PUT | `/api/auth/accounts` · `/api/members/{id}/credentials` | Accesos de personas |
| GET · POST · DELETE | `/api/auth/tokens` · `/api/members/{id}/tokens` · `/api/auth/tokens/{id}` | Tokens de agentes |
| GET | `/api/issues/{key}/activity?limit=` · `/api/projects/{key}/activity?limit=` | Historial (máx. 200, más reciente primero) |
| PUT | `/api/projects/{key}/methodology` | `{methodology: clave del catálogo \| null}` (solo personas) |
| GET | `/api/agents/status` · `/api/agents/runs?agentId=&issueKey=` | Si los agentes están activos / ejecuciones recientes |
| GET · POST | `/api/projects/{key}/docs` | Documentos del proyecto y sus issues / crear desde plantilla `{kind, issueKey?}` |
| GET | `/api/issues/{key}/docs` | Documentos de un issue |
| GET · PUT | `/api/docs/{id}` | Leer / guardar versión `{baseVersion, content, note}` |
| GET · POST | `/api/docs/{id}/versions[/{n}]` · `…/{n}/restore` | Versiones / restaurar (crea una versión nueva) |
| GET · PUT · DELETE | `/api/projects/{key}/repository` | Repositorio vinculado `{kind: GITHUB\|LOCAL, location, branch?, token?}` (solo personas; el token nunca se devuelve) |
| GET | `/api/projects/{key}/sync` | Estado de cada documento y archivos solo en el repo |
| GET | `/api/projects/{key}/sync/docs/{id}/compare` | Contenido del tablero y del archivo |
| POST | `/api/projects/{key}/sync/docs/{id}/export` · `…/import` | `{force}`: `true` solo para resolver un conflicto; exportar en GitHub abre o actualiza el PR |
| POST | `/api/projects/{key}/sync/export` | Varios documentos en un solo commit / PR `{documentIds, forced?}` |
| POST | `/api/projects/{key}/sync/import-new` · `/sync/safe` | Importar un grupo nuevo `{group, issueKey \| newIssueTitle}` / sincronizar lo seguro |

Errores en formato Problem Details (RFC 9457), con mensajes en español: 400, 401, 403, 404, 409, 422 y 429.

## Hoja de ruta

1. **Tablero MVP** ✅ proyectos, issues, arrastrar y soltar, filtros, comentarios, equipo.
   - **Tipos de proyecto y flujos configurables** ✅ Software, Ventas y Tareas simples.
2. **Base para agentes** ✅ autenticación (personas y tokens de servicio), eventos de dominio con
   entrega garantizada e historial de actividad.
3. **Contexto de proyectos de software** ✅ metodología (SDD, ODD o Libre), documentos versionados y
   sincronización en tres vías con GitHub (Pull Requests) y con carpetas locales. Falta el webhook
   de GitHub, que requiere desplegar el tablero.
4. **Fábrica de agentes**: módulo `agents` ✅ con el **Agente PO**. Siguen el arquitecto, backend,
   frontend, QA, DevOps y marketing. Detalle de lo planeado: un módulo `agents` que escucha `IssueAssigned` e `IssueMoved`. Cuando un issue se asigna a
   un agente o entra a su columna, el agente trabaja sobre el repositorio con Claude Agent SDK,
   comenta el resultado y mueve el issue (por ejemplo Backend → En QA → QA valida → Hecho o vuelve
   a En progreso con evidencia).
5. **Agentes de negocio**: marketing y ventas a partir de lo entregado en "Hecho".

Plan de la fábrica (`docs/diseno-fabrica.md`): **Fase 0** ✅ base configurable (espacios de trabajo,
catálogo por espacio, interfaz nueva con PrimeReact). Siguen: 1 etapas, roles y aprobaciones
humanas · 2 servidor MCP del tablero · 3 proyecto nuevo desde plantillas · 4 PO y arquitecto por
MCP · 5 desarrollo y QA · 6 release.

> Antes de exponer la API fuera de `127.0.0.1`: ponla detrás de HTTPS, deshabilita Swagger en
> producción y usa una contraseña inicial fuerte. Si hay varias instancias, mueve el bloqueo de login
> (hoy en memoria) a la base o a Redis.

## Agentes locales (Fase 2)

El servidor MCP del tablero es estándar: sirve cualquier cliente de IA con MCP (Claude Code, Codex, Cursor, OpenCode, Cline…). Todo lleva el nombre del espacio de trabajo, no el del producto: en el espacio `adma`, el servidor se llama `adma` y el token va en `TABLERO_ADMA_TOKEN`.

1. En el tablero, **Agentes locales → Crear conexión** (una por equipo). Guarda el token en tu shell, nunca en el repo.
2. Elige tu cliente y descarga el kit en la raíz del repo:
   - **Claude Code:** `.mcp.json`, un subagente por rol (`.claude/agents/<espacio>-<rol>.md`) y el comando `/pendientes`. Haz `unset ANTHROPIC_API_KEY` para usar tu plan.
   - **Codex:** `.codex/config.toml` (proyecto de confianza; `bearer_token_env_var`) y `AGENTS.md` con el flujo de trabajo.
   - **Otro cliente MCP:** `mcp-servidores.json` con URL y cabecera para adaptar, y `AGENTS.md`.
3. Pide «trabaja mis pendientes». El historial del issue muestra desde qué cliente se trabajó.

Solo se atienden los issues que supervisas: los que asignaste a un agente o llevaste a una etapa que cubren agentes. Las puertas siguen siendo de las personas.
