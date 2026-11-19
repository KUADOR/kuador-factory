# Kuador Go

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
cp .env.example .env   # primera cuenta y BOARD_SECRETS_KEY
JAVA_HOME=~/.sdkman/candidates/java/25.0.2.fx-zulu ./mvnw spring-boot:run

# 3. Web → http://localhost:3600
cd kuador-board-web
cp .env.example .env.local   # PRIMEUI_LICENSE: clave PrimeUI (sin ella sale un aviso de licencia)
corepack pnpm install && corepack pnpm dev
```

Pruebas de la API: `./mvnw test` (dominio, casos de uso y límites entre módulos) y `./mvnw verify`
(además, aislamiento entre espacios contra un Postgres real con Testcontainers; requiere Docker).

## Desplegar

Imágenes Docker de la API y la web, CI que las publica en GHCR y guía de Dokploy (web y API con dominio propio; por el de la API
solo entran `/mcp`, el kit de agentes, el webhook de GitHub y la salud): [docs/despliegue.md](docs/despliegue.md).
Monitoreo con Grafana, Prometheus, Loki y Alloy (tablero y alertas por correo): [docs/monitoreo.md](docs/monitoreo.md).

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
- **Planes y licencias** (módulo `billing`): los planes que se venden viven en la tabla `plan`
  (nombre, frase, precio mensual, moneda, características, máximo de personas y de tableros, si
  incluyen la **fábrica de software**, días de prueba, orden, recomendado, a la venta). Se ven sin
  sesión en `GET /api/public/plans` (presentación y alta) y los edita en *Plataforma* quien figura en
  `BOARD_PLATFORM_ADMINS`. Cada espacio tiene una fila en `workspace_subscription`: al crearlo elige
  plan (los de pago empiezan con su prueba, una sola por espacio); desde *Espacio de trabajo → Plan*
  lo cambia. Rige el plan contratado mientras está activo o en prueba vigente; si no, el gratuito.
  Bajar de plan no borra nada: solo impide crear personas o tableros por encima del límite y usar la
  fábrica (plantilla *Fábrica de software*, metodologías con agentes, piezas en GitHub y
  `create_tasks`). Los espacios sin fila (instalación propia o anteriores a los planes) no tienen
  límites. Un pago también se puede registrar a mano en *Plataforma* (estado activo y hasta cuándo).
- **Pagos con pasarelas configurables**: cada pasarela es una fila de `payment_provider` (código,
  tipo, nombre, modo sandbox/real, credenciales cifradas como JSON, activa, orden) que se edita en
  *Plataforma → Pasarelas de pago*. El tipo elige un adaptador (`PaymentGateway`; hoy `PAYPAL`, con la
  API de órdenes v2) y el formulario muestra las credenciales que ese adaptador declara: sumar un
  proveedor es escribir un adaptador, sin tocar el resto. Se paga 1 o 12 meses de un plan desde
  *Espacio de trabajo → Plan* (y al terminar el alta con un plan de pago); la persona aprueba en la
  pasarela, vuelve a `/workspace/plan/payment` y el cobro se confirma. Los avisos de la pasarela
  (`/api/payments/webhook/{código}`, verificados con la pasarela) activan el plan aunque la persona no
  vuelva; un mismo pago nunca extiende la licencia dos veces.
- **Avisos de vencimiento y recibos**: cada hora (`board.billing.reminders-every`) se revisan las pruebas
  y los periodos pagados que vencen. A los dueños del espacio (rol OWNER; si no hay, quienes editan)
  les llega un aviso por correo y Telegram los días configurados en *Plataforma → Avisos de
  vencimiento* (por defecto 7, 3 y 1) y otro el día que vence. Cada aviso sale una vez por
  vencimiento (`billing_notice`); si se pasaron varios umbrales, solo el más cercano. Al confirmarse
  un pago llega el **recibo** (plan, periodo, monto, fecha, pagado hasta, medio y referencia) a los
  dueños y a quien pagó. En el tablero, quienes editan ven una franja desde el primer aviso o si el
  plan venció; *Plan* muestra el historial de pagos. Estos avisos no se pueden apagar.
- **Marca del espacio** (*Marca y empresa*): el color base pinta botones principales, selección y foco
  para todo el equipo; de ese color se derivan los tonos y el texto legible en ambos temas.
- El catálogo de fábrica tiene **versión** (`catalog/defaults.yaml`, `version` y `releases`): al
  arrancar, cada espacio recibe una sola vez lo que se agregó después de su versión, sin tocar lo que
  el equipo editó ni revivir lo que borró. Así llegaron los servidores MCP de GitHub.
- **Catálogo de la plataforma** (*Plataforma → Catálogo de la plataforma*, solo `BOARD_PLATFORM_ADMINS`):
  lo que reciben todos los espacios vive en la base (`platform_catalog_entry`), sembrado la primera
  vez desde `catalog/defaults.yaml`. Se edita en borrador y se **publica una versión**
  (`platform_catalog_release`): lo agregado llega a todos los espacios; lo actualizado llega a los
  espacios que no habían modificado esa entrada (se compara con lo publicado antes) y se respeta la
  versión de los que sí. Quitar una entrada solo deja de repartirla. En roles se edita también el
  agente que nace con cada espacio (nombre y color).
- **Paquetes por industria** (`catalog_package`, editables en *Plataforma*): al registrarse la empresa
  elige a qué se dedica; arranca con el tablero inicial del paquete (con su equipo de agentes) y al
  crear tableros ve primero sus plantillas («Recomendada»).
- El **catálogo** es configurable por espacio en la web (*Catálogo*): plantillas de flujo,
  metodologías (documentos, rutas en el repo, quién los escribe), roles de agentes (instrucciones,
  herramientas, automatización) y servidores MCP. Lo de fábrica se edita y se restaura; lo propio se
  crea y se borra si nadie lo usa (409 si está en uso). Solo las personas lo editan.

## Eliminar un tablero

*Configuración → General → Eliminar tablero*, escribiendo la clave para confirmar (`DELETE
/api/projects/{clave}?confirm={clave}`; solo quien administra el espacio, nunca un agente). La base borra en
cascada columnas, tarjetas, comentarios, documentos con sus versiones, sincronización, enlace al repo
y piezas; con el evento `ProjectDeleted` cada módulo borra lo suyo que no cuelga del tablero:
actividad, corridas de agentes y adjuntos (con sus archivos en Garage). Los repositorios en GitHub
no se tocan. Libera el cupo de tableros del plan.

## Adjuntos

- Cada tarjeta tiene la pestaña **Adjuntos**: se sueltan o se eligen archivos (hasta
  `BOARD_STORAGE_MAX_FILE_SIZE`, 25 MB por defecto); las imágenes se ven en miniatura y los PDF se
  abren en otra pestaña. Suben y borran quienes editan; ven y descargan todos.
- El archivo vive en un almacenamiento compatible con S3, **Garage** (`infra/garage`), en la red
  interna; la tabla `attachment` guarda nombre, tipo, tamaño y la clave del objeto
  (`espacio/tarjeta/id`, nunca el nombre). Las descargas pasan por la web (`/attachments/file/{id}`) y
  la API con la sesión; solo imágenes y PDF se muestran en línea, el resto se descarga
  (`nosniff`). Al borrar una tarjeta se borran sus archivos.
- Los agentes ven los adjuntos en `get_issue` y leen los de texto (Markdown, TXT, CSV, JSON, YAML,
  XML, hasta 200 KB) con `read_attachment`.
- Instalación en local y en Dokploy, problemas conocidos, respaldo y rotación de claves:
  [docs/garage.md](docs/garage.md).

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
- **Recuperar la contraseña** (`/reset-password`, enlace en el login): llega un correo con un enlace y un
  código de 6 dígitos; con cualquiera de los dos se elige la nueva y se cierran todas las sesiones.
  Vale 30 minutos, una vez, con 5 intentos de código; un correo por minuto y 5 por hora por
  dirección. La respuesta es la misma exista o no la cuenta. El correo sale por `Mailer`
  (`shared/mail`), el mismo SMTP del alta.
- **Invitar por correo** (*Equipo → Agregar miembro → Persona* con correo, o *Invitar por correo* en
  una persona sin acceso): la persona queda en el equipo (cuenta para el límite del plan) y recibe un
  enlace a `/invitation` para elegir su contraseña y entrar. Vale 7 días y una vez; reenviar anula el
  anterior. La alternativa sigue siendo poner una contraseña temporal.
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

Activar: cada espacio pone su clave de IA en *Conexiones → IA* (no hay clave del servidor). Sin clave,
cada asignación se registra como `SKIPPED`, con el motivo. **Cada asignación al Agente PO consume tokens.** Configurable:
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

### Tiempo real

El módulo `live` reenvía esos eventos, después del commit, a los navegadores abiertos por
Server-Sent Events: `GET /api/live?project=KEY` (del espacio de quien pide). Cada aviso
(`cambio`) lleva solo `proyecto`, `issue`, `tipo` y `actor`, nunca contenido: el navegador vuelve
a pedir los datos y la API decide qué ve cada quien. La web lo reenvía en `/live` (el navegador
nunca llama a la API) y `LiveUpdates` recarga la página del proyecto y los paneles del issue abierto,
sin pisar lo que alguien está escribiendo. Las conexiones viven en memoria (una réplica) con un
latido cada 25 s; si se cortan, el navegador se reconecta y recarga.

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

## Estados de sincronización

En *Docs → Sincronizar* se ve el estado de cada documento frente a la rama principal del repo de GitHub.

| Estado | Significa | Acción |
|---|---|---|
| Sincronizado | Igual en ambos lados | — |
| Cambió en el tablero / Solo en el tablero | Editado aquí después de sincronizar / nunca exportado | Exportar (abre o actualiza el PR del tablero) |
| Cambió en el repo | Editado en el repo (tú, Spec Kit, un editor) | Importar como versión nueva |
| **Conflicto** | Cambió en ambos lados | Comparar (diff) y elegir un lado |
| Borrado en el repo | Se sincronizó y luego se borró el archivo | Exportar para recrearlo, o dejarlo así |

- Sincroniza en **tres vías**: guarda la versión del tablero y el SHA-256 del archivo de la última
  sincronización, para saber qué lado cambió.
- *Sincronizar lo seguro* importa lo fusionado y lleva los cambios del tablero a un Pull Request.
  **Los conflictos nunca se resuelven solos.**
- **Importar lo existente**: detecta `.specify/memory/constitution.md`, `PRODUCT.md`, `AGENTS.md`,
  `docs/architecture.md`, `docs/glossary.md`, `specs/*/{spec,plan,tasks}.md`, `odd/tasks/*.md` y
  `docs/issues/*.md`. Una carpeta de spec se importa a un issue existente o a uno nuevo, conservando
  su ruta real (p. ej. `specs/001-lead-capture/`).
- Solo GitHub (con token o con la app). La sincronización con una carpeta del servidor se quitó: con
  varios clientes no es segura.
- Hexagonal: `RepositoryFiles` es un puerto; GitHub (token y app) son sus adaptadores.

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
| GET · POST | `/api/projects/{key}/components` | Piezas del proyecto · sumar una (carpeta por PR a develop, o repo propio) |
| POST | `/api/projects/{key}/components/setup` | Armar el proyecto en GitHub: repo del proyecto con sus bases y sus piezas (monorepo o un repo por pieza) |
| PUT | `/api/members/{id}/access` | Nivel de acceso de una persona `{access: OWNER\|ADMIN\|EDIT\|VIEW}` (solo quien administra; administradores y dueños, solo un dueño; nadie cambia el suyo; siempre queda un dueño) |
| PUT | `/api/projects/{key}/stages` | Etapas `{stages: [{column, roles, gate, approvers, rejectTo}], maxRework}` (solo personas) |
| POST | `/api/issues/{key}/take` · `/deliver` · `/review` | Tomar de la etapa / entregar `{comment?}` / cerrar una puerta `{approve, comment}` (solo personas aprobadoras) |
| PUT | `/api/projects/{key}` · `/api/projects/{key}/transitions` | Nombre y descripción / reglas `{restricted, allowed: [{from, to}]}` (solo personas) |
| GET · PUT · DELETE | `/api/catalog/{workflow-templates\|methodologies\|roles\|mcp-servers}[/{key}]` | Catálogo del espacio; `PUT` crea o actualiza con `version` (solo personas) |
| POST | `/api/catalog/{tipo}/{key}/restore` | Volver a los valores de fábrica |
| GET/POST | `/api/projects` | Listar (solo los tableros que ve quien pide) / crear proyectos `{key, name, description?, templateKey}`; quien lo crea queda en él |
| GET · POST · DELETE | `/api/projects/{key}/people` · `/api/projects/{key}/people/{memberId}` | Personas del tablero · sumar `{memberId}` · quitar (sumar y quitar, solo quien administra). Quien edita o solo mira ve únicamente los tableros donde está; dueños y administradores, todos |
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
| GET · PUT · DELETE | `/api/projects/{key}/repository` | Repositorio vinculado `{kind: GITHUB\|GITHUB_APP, location, branch?, token?, installationId?}` (solo personas; el token nunca se devuelve) |
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

## Agentes en el tablero (con la clave de IA de la empresa)

- Cada agente trabaja **en la PC de una persona** (LOCAL: su Claude Code o Codex por MCP, con
  `/pendientes`; ideal para software, que necesita repo y terminal) o **en el tablero** (SERVER: trabaja
  solo cuando una persona le asigna una tarjeta). Se elige en *Configuración → Equipo de agentes* de
  cada tablero (tabla `agent_profile`, con modelo opcional por agente).
- **Equipo por tablero** (`project_agent`): un tablero nuevo trae el de su plantilla (`agents` en la
  plantilla, o los roles de sus etapas); después se suman agentes del espacio, se crean desde el
  tablero (rol del catálogo + especialidad + dónde trabaja) o se quitan.
- **Clave de IA** (*Conexiones → IA*, `workspace_ai`): la clave de API de Anthropic de la empresa, que
  se comprueba con Anthropic, se guarda cifrada y nunca se vuelve a mostrar; el consumo va a su cuenta.
  Modelo por defecto del espacio y, si se quiere, uno por agente.
- **Cómo trabaja un agente en el tablero** (`BoardAgent`): arma el pedido con el manual de su rol, las
  reglas del tablero y la tarjeta (descripción y comentarios), sube los adjuntos al entorno de
  ejecución de código de Anthropic (pandas, openpyxl, matplotlib, reportlab), calcula con código
  (nunca estima cifras), deja sus entregables en `$OUTPUT_DIR`, y termina con `deliver` o
  `ask_human`. El tablero adjunta lo que generó, comenta el resumen y devuelve la tarjeta a quien se la
  asignó (le llega el aviso). Si falta la clave o algo falla, lo dice en la tarjeta. Los archivos
  subidos y generados se borran de Anthropic al terminar.
- **Auditoría** (*Configuración → Auditoría*; toda la plataforma en *Plataforma → Auditoría*): cada
  escritura, descarga de adjuntos y exportación queda registrada con quién, cuándo, la IP y el navegador
  de la persona y el resultado, incluidos los logins fallidos y los accesos denegados. Vista «Seguridad»
  (accesos, permisos, claves, conexiones, pagos) o «Todo»; filtros por periodo y texto; exportación CSV.
  La tabla `audit_log` es solo de agregar: un disparador impide editar, borrar o vaciarla. Nunca guarda
  contenidos, contraseñas ni claves. La web reenvía la IP de la persona (`X-Kuador-Client-IP`).
- **Seguridad**: el primer acceso de una persona solo se da una vez (después solo ella cambia o recupera su
  contraseña); un correo de administración de la plataforma no se asigna a mano; cambiar o recuperar la
  contraseña cierra también las conexiones de agentes locales; los códigos de 6 dígitos cuentan cada intento
  de forma atómica; el login frena por correo+IP, por IP y por correo (nadie bloquea a otro desde fuera);
  límites por IP en recuperación, alta y registro OAuth; los agentes no pueden fabricar resultados enormes
  (funciones prohibidas, tope de celdas, bombas zip, dos trabajos a la vez); los servidores MCP STDIO solo
  con lanzadores conocidos; cabeceras anti-iframe en la web. `BOARD_WEB_SECRET` (el mismo en web y API)
  cierra el resto de la API a todo lo que no sea la web y hace confiable la IP de la auditoría.
- **Proveedores de IA** (*Plataforma → Proveedores de IA*): Claude (Anthropic), ChatGPT (OpenAI) y
  DeepSeek, editables (dirección de la API, modelos, activo). Cada empresa elige uno en *Conexiones → IA*
  con su clave de API (cambiar de proveedor pide su clave). Con Claude el agente ejecuta código en el
  entorno de Anthropic; con los compatibles con OpenAI usa las **herramientas del tablero**: leer adjuntos
  (PDF, Word, Excel, CSV, texto), consultar planillas con SQL de solo lectura en una base en memoria
  (usuario sin permisos de administración) y crear PDF con títulos, indicadores, tablas y gráficos, o CSV.
  El agente decide qué usar según lo que pide la tarjeta. No ven imágenes ni PDF escaneados. El Agente PO
  sigue trabajando solo con Claude.
- **Consumo de IA** (*Conexiones → IA*): tokens y costo estimado del mes, en total y por agente, a
  partir de las ejecuciones de los agentes en el tablero y de los precios por modelo que configura la
  plataforma (*Plataforma → Precios de modelos de IA*, USD por millón de tokens; no están fijos en el
  código). Con un **tope mensual**, los agentes no arrancan al llegar a él y lo dicen en la tarjeta.
  No incluye el tiempo de ejecución de código ni a los agentes en la PC de una persona.
- **Documentos base antes del repo** (software, proyecto nuevo): el asistente de inicio va Metodología →
  Documentos base → Repositorio, y ese paso no se salta. El repo no se crea (la API responde 422) hasta
  que estén redactados los documentos base de la metodología (en ODD, Producto y Reglas para agentes; en
  las demás, todos los del proyecto) o, sin metodología, el objetivo del tablero. Un documento cuenta como
  redactado cuando ya no es la plantilla. Un proyecto que ya existe se vincula sin este freno y el agente
  propone los documentos leyendo el código.
- **Primera experiencia fuera de software**: el asistente de inicio de un tablero que no es de software
  pide la clave de IA, los avisos y un *primer pedido* (qué necesitas, archivos y para qué agente, en un
  paso); sus agentes nacen trabajando en el tablero, «Etapas» y «Reglas» quedan en *Avanzado* y
  «Agentes locales» solo aparece si hay tableros de software. Tras el alta con paquete se entra directo
  al asistente del tablero inicial. Si un rol del equipo no tiene agente en el espacio, se crea.
- Ejemplo: el rol **Analista de datos e informes** (plantillas Ventas y Tareas): adjuntas un Excel con
  las ventas, le asignas «Informe de ventas diarias de octubre» y te devuelve un PDF con totales,
  gráficos y observaciones.

## Agentes locales (Fase 2)

El servidor MCP del tablero es estándar: sirve cualquier cliente de IA con MCP (Claude Code, Codex, Cursor, OpenCode, Cline…). Todo lleva el nombre del espacio de trabajo, no el del producto: en el espacio `adma`, el servidor se llama `adma`.

**Conectarse = un comando y un inicio de sesión** (OAuth 2.1 con PKCE; no hay token que copiar):

- **Claude Code:** en un repo creado por el tablero ya viene el `.mcp.json`; si no, `claude mcp add --transport http --scope user <espacio> <URL>/mcp`. Después `claude mcp login <espacio>`: se abre el navegador, la persona entra al tablero y aprueba. Sin `ANTHROPIC_API_KEY` definida, usa su plan.
- **Codex:** `codex mcp add <espacio> --url <URL>/mcp` y `codex mcp login <espacio>`.
- **Clientes sin OAuth:** token de conexión (Agentes locales → Crear token) en la cabecera `Authorization: Bearer`.

Cada inicio de sesión queda como una conexión de esa persona en **Agentes locales**, donde se revoca.

**El kit vive en el repo.** Al crear un repo, el tablero commitea `.mcp.json` (sin cabeceras), el comando `/pendientes`, un subagente por rol (`.claude/agents/<espacio>-<rol>.md`) y `.codex/config.toml`. Si cambian los roles, las reglas o los idiomas, **Sincronizar → Kit de agentes** lo propone por PR. Sin archivos, el mismo pedido llega como prompt del servidor (`/mcp__<espacio>__pendientes`). El .zip queda para repos que no creó el tablero.

**Herramientas** (en inglés): `pending_work`, `get_issue`, `role_manual`, `take_issue`, `add_comment`, `estimate`, `deliver`, `send_back`, `ask_human`, `read_document`, `write_document`, `create_tasks`.

**Épicas con tareas.** Una épica pasa por PO → aprobación → arquitecto como cualquier issue. Al terminar el plan, el arquitecto crea con `create_tasks` una tarjeta por cada tarea del `tasks.md`, hija de la épica, con su rol (BACKEND, FRONTEND, DEVOPS…) y sus dependencias. Esperan en Backlog. Cuando la épica llega a Desarrollo, queda en **Reparto por aprobar**: una persona revisa en el detalle de la épica qué tarea hace cada rol y qué espera a qué, y **aprueba el reparto** (arrancan las tareas) o **pide cambios** (la épica vuelve al arquitecto que hizo el reparto, con el comentario, y lo rehace). Antes de aprobar se pueden editar o borrar tareas como cualquier tarjeta. Cada tarea la toma el agente de su rol, en su propia rama y PR; las que dependen de otra no aparecen en pendientes hasta que esa termina. Cada tarea es de un rol que escribe en el repo (BACKEND, FRONTEND, DEVOPS): el contrato, el modelo de datos y los ADR van en el plan, y si deben quedar en el repo son la tarea 1 de backend. Las tareas arrancan siempre en la columna donde se construye (Desarrollo); en las etapas siguientes (QA, revisión) las atiende el rol de esa etapa, no el que las construyó. La épica espera en Desarrollo y no se puede adelantar mientras tenga tareas abiertas; si estaba más adelante al aprobar el reparto, vuelve sola a Desarrollo. Una historia sin épica que el plan reparte pasa a ser épica al llamar a `create_tasks`. Una tarea termina cuando se aprueba su PR (pasa directo a Hecho, sin release propio); cuando terminan todas, la épica pasa sola a Release: un release por épica. El tablero muestra en cada tarjeta el rol, «Espera a …» y el avance de la épica (`2/5 tareas`).

**En paralelo:** `/pendientes --paralelo` lanza a la vez (hasta 3) los subagentes de las tareas libres; cada uno trabaja en su propio `git worktree`. Consume más rápido los límites de tu suscripción.

**Presentación del producto** (`/product`): quien entra a la raíz sin sesión ve la landing de Kuador Go (marca de kuador.com: emblema, paleta noche + sol ecuatorial, Archivo semiexpandido y Martian Mono); con sesión, la raíz sigue siendo el tablero. Sin clientes ni cifras inventadas: la vista del tablero usa datos de ejemplo.

**Asistente de inicio** (`/projects/<clave>/setup`): al crear un proyecto se abre solo y lo deja listo en pasos que se pueden saltar: metodología, repositorio y piezas (con la GitHub App o vinculando uno existente), cliente de IA, notificaciones y primer trabajo (una épica «Esqueleto del sistema» que entra a la etapa del PO). El tablero muestra «Faltan N pasos» hasta terminar (se puede ocultar) y Configuración tiene el acceso para volver.

**Notificaciones** (Mi cuenta → Notificaciones): avisos por correo (el mismo SMTP del alta) y por Telegram solo cuando algo necesita a la persona: le asignan un issue (p. ej. un agente le pide una decisión, con la pregunta en el aviso), un issue espera su aprobación en una puerta o un reparto de tareas espera que lo apruebe. Cada persona vincula su Telegram con un enlace de un solo uso, elige canales y avisos, y puede enviarse una prueba. Los envíos van por una cola con reintentos. En local, el bot recibe mensajes con `getUpdates` (en producción, por webhook).

**Telegram por espacio** (Conexiones → Telegram): cada empresa conecta su propio bot (lo crea con
@BotFather y pega el token, que se valida con `getMe` y se guarda cifrado con `BOARD_SECRETS_KEY`) y
vincula grupos: agrega el bot al grupo y escribe ahí `/vincular CÓDIGO` (un uso, 15 minutos). Cada
grupo elige qué recibe (aprobaciones, repartos, asignaciones) y recibe un aviso por evento, no uno por
persona. Cada bot tiene su webhook `/api/telegram/webhook/{espacio}` con su propio secreto. Cambiar o
quitar el bot desvincula los chats y grupos del anterior. Sin bot propio, el espacio usa el de la
plataforma (`BOARD_TELEGRAM_BOT_TOKEN`/`_USERNAME`, opcional).

**Idiomas** (Empresa e idiomas): idioma del equipo (specs, planes, comentarios, textos para el usuario final; por defecto español) e idioma del código (nombres, comentarios, pruebas, commits, PRs, OpenAPI; por defecto inglés). Todos los agentes lo reciben como regla de la fábrica.

Solo se atienden los issues que supervisas: los que asignaste a un agente o llevaste a una etapa que cubren agentes. Las puertas siguen siendo de las personas.
