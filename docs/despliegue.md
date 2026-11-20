# Despliegue en Dokploy

Todo corre en el servidor de Dokploy, cada pieza como un servicio aparte, con dos dominios:
`go.kuador.com` (la web) y `go-api.kuador.com` (la API).

**Antes de empezar**: en el DNS de `kuador.com`, registros `A` `go` y `go-api` → IP del VPS (sin
proxy de Cloudflare, o con modo SSL *Full*). Dokploy pide el certificado HTTPS cuando ya resuelven.

```
Internet ──HTTPS──▶ Traefik (Dokploy)
                      │  go.kuador.com      ──▶ kuador-board-web (Next.js, :3600)
                      │  go-api.kuador.com  ──▶ kuador-board-api (:8091), solo /mcp, OAuth, el kit, el webhook y la salud
                      ▼
        kuador-board-web ──red interna──▶ kuador-board-api ──▶ PostgreSQL (servicio de BD de Dokploy)
                                                          └──▶ Garage (adjuntos, S3, http://garage:3900)
```

- **Por el dominio de la API solo entran** el MCP de los clientes de IA de cada persona
  (`/mcp`), su inicio de sesión OAuth (`/.well-known/oauth-protected-resource[/mcp]`,
  `/.well-known/oauth-authorization-server`, `/oauth/register`, `/oauth/token`; la pantalla de permiso
  es `https://go.kuador.com/oauth/authorize`, por eso `BOARD_WEB_URL` debe ser la URL pública de la web),
  el kit de agentes (`/api/agent-kit`, `/api/agent-kit.zip`), los webhooks de los bots de Telegram
  (`/api/telegram/webhook[/{clave}]`, con su secreto), los avisos de las pasarelas de pago
  (`/api/payments/webhook/{código}`, verificados con la pasarela), el webhook de GitHub
  (`/api/github/webhook`) y la salud (`/readyz`, `/livez`, `/actuator/health`). Lo hace la propia API (`BOARD_PUBLIC_HOST_GATE=true`):
  cualquier otra ruta pedida con el host de `BOARD_PUBLIC_URL` responde 404. El resto de la API lo
  llama el servidor de Next por la red interna, con el host interno; el navegador nunca habla con la API.
- **Las imágenes** las construye GitHub Actions (`.github/workflows/ci.yml`) y las publica en GHCR:
  `ghcr.io/<dueño>/kuador-board-api` y `ghcr.io/<dueño>/kuador-board-web`, con las etiquetas
  `latest` (último `main`) y `sha-<commit>` (para volver atrás).
- **Las variables de entorno viven solo en Dokploy.** Nunca en GitHub ni en el repositorio. Las
  imágenes no incluyen ningún `.env` (`.dockerignore`).

## 1. Registro de imágenes

Si Dokploy ya tiene el registro `ghcr.io` (lo usa la landing), sirve el mismo. Si no: token
(classic) con `read:packages` y en Dokploy **Settings → Registry → Add registry** (`ghcr.io`, tu
usuario, el token).

## 2. Base de datos

En el proyecto de Dokploy, **Database → PostgreSQL** (versión 18), sin puerto externo. Flyway crea
las tablas en el primer arranque. Programa respaldos diarios en la pestaña **Backups**.

## 3. Servicio de la API

**Application**, proveedor Docker, imagen `ghcr.io/<dueño>/kuador-board-api:latest`. **Réplicas: 1**
(la caché de credenciales de GitHub y los estados de los flujos viven en memoria y en la base).

**Domains**: `go-api.kuador.com`, path `/`, puerto `8091`, HTTPS.

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://<app-name-de-la-bd>:5432/<base>
SPRING_DATASOURCE_USERNAME=
SPRING_DATASOURCE_PASSWORD=

# openssl rand -base64 32 — cifra las claves de GitHub guardadas. Si se pierde, hay que volver a registrar la app.
BOARD_SECRETS_KEY=

BOARD_PUBLIC_URL=https://go-api.kuador.com
BOARD_WEB_URL=https://go.kuador.com
# Por go-api.kuador.com solo entran /mcp, su OAuth, el kit, el webhook y la salud.
BOARD_PUBLIC_HOST_GATE=true
# openssl rand -hex 32 — el MISMO valor que en la web. Con él, el resto de la API solo responde a la web (por
# cualquier dominio o IP) y la auditoría confía en la IP de cada persona que reenvía la web.
# Ponlo primero en la web y después en la API: si solo lo tiene la API, la web deja de funcionar.
BOARD_WEB_SECRET=

# Primera cuenta (solo si la base está vacía). Cámbiala después de entrar.
BOARD_BOOTSTRAP_ADMIN_EMAIL=
BOARD_BOOTSTRAP_ADMIN_PASSWORD=

# Correo de los códigos del alta (SMTP). Sin SMTP nadie puede crear espacios nuevos.
SPRING_MAIL_HOST=smtp.hostinger.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=
SPRING_MAIL_PASSWORD=
BOARD_MAIL_FROM=Kuador Go <no-reply@kuador.com>
BOARD_MAIL_LOG_CODES=false

# Quién administra la plataforma (planes y licencias de todos los espacios): correos separados por coma.
# Esa persona ve «Plataforma» en el menú. Vacío = nadie.
BOARD_PLATFORM_ADMINS=

# Bot de Telegram de respaldo de la plataforma (opcional). Cada espacio conecta el suyo desde la web
# (Conexiones → Telegram); este solo se usa para los espacios que no tienen bot propio.
# Al arrancar, la API registra el webhook de cada bot en https://go-api.kuador.com/api/telegram/webhook/{clave}.
BOARD_TELEGRAM_BOT_TOKEN=
BOARD_TELEGRAM_BOT_USERNAME=

# Notificaciones push de la app instalada (Mis decisiones, aprobaciones, preguntas de agentes). Genera el par una
# sola vez con `npx web-push generate-vapid-keys` (base64url) y no lo cambies: los dispositivos suscritos dependen
# de la clave pública. Sin estas variables, la app se instala igual pero sin notificaciones push.
BOARD_PUSH_VAPID_PUBLIC_KEY=
BOARD_PUSH_VAPID_PRIVATE_KEY=
BOARD_PUSH_SUBJECT=mailto:soporte@kuador.com

# Documentación de la API apagada en producción.
SPRINGDOC_API_DOCS_ENABLED=false
SPRINGDOC_SWAGGER_UI_ENABLED=false

# Monitoreo (docs/monitoreo.md): métricas en un puerto que Traefik no publica y logs en JSON para Loki.
MANAGEMENT_SERVER_PORT=9464
LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
```

La API no tiene clave de IA propia: los agentes del tablero usan la clave de cada espacio (*Conexiones →
IA*) y los agentes locales corren en el Claude Code (o Codex, u otro cliente) de cada persona, con su
propia suscripción, conectados al MCP.

## 3b. Garage (adjuntos)

Los adjuntos de las tarjetas viven en Garage (compatible con S3) en la red interna. Guía completa,
con los pasos tal como se instaló y los problemas conocidos: **[garage.md](garage.md)**.

Resumen:

1. Servicio *Compose* en Dokploy con `infra/garage/compose.dokploy.yaml` y las variables
   `GARAGE_RPC_SECRET` y `GARAGE_ADMIN_TOKEN`.
2. Por SSH, una vez: copiar `infra/garage/setup.sh` al servidor y correr
   `CONTAINER=$(docker ps --filter ancestor=dxflrs/garage:v2.5.0 --format '{{.Names}}' | head -1) ./setup.sh`.
3. Pegar las variables `BOARD_STORAGE_S3_*` que imprime en el API, redesplegar y ver en el log
   `Adjuntos en el bucket kuador-adjuntos`.

## 4. Servicio de la web

**Application**, imagen `ghcr.io/<dueño>/kuador-board-web:latest`. Dominio `go.kuador.com`,
path `/`, puerto `3600`, HTTPS.

```dotenv
BOARD_API_URL=http://<app-name-de-la-api>:8091
BOARD_PUBLIC_API_URL=https://go-api.kuador.com
PRIMEUI_LICENSE=
# El mismo valor que en la API (ver arriba).
BOARD_WEB_SECRET=
```

## 5. GitHub App

Con el tablero ya en su dominio, entra como dueña del espacio que opera la plataforma y ve a
**Conexiones → Registrar la app en GitHub**. El manifiesto lleva las URLs públicas, así que GitHub
vuelve al tablero desplegado. Una app registrada desde `localhost` queda con URLs locales: no se
reutiliza en producción.

## 5b. Monitoreo

Grafana, Prometheus, Loki y Alloy en un servicio Compose aparte: ver [monitoreo.md](monitoreo.md).

## 6. Ramas, pipeline y despliegues

El trabajo va de ramas cortas a `develop` (pruebas) y de `develop` a `main` (producción). Cada imagen se
construye **una sola vez**, en `develop`; producción recibe esa misma imagen, ya probada y escaneada.

| Rama o evento | Qué corre | Imágenes | Despliega |
|---|---|---|---|
| PR de una rama de trabajo → `develop` | Pruebas (API, web, runner en Linux) y escaneos; con todo en verde deja la marca `ci/arbol-probado` | No | No |
| Push a `develop` (al fusionar) | Construcción, escaneo de imagen, publicación y runner en Windows. Pruebas y escaneos de código solo si el PR no probó ya el mismo código (sin marca, o `develop` avanzó entre medio) | `sha-<commit>` y `develop` | Pruebas (entorno `staging`) |
| PR `develop` → `main` | Nada si develop ya publicó imágenes probadas de ese commit (solo lo verifica); si no, pruebas y escaneos | No | No |
| Push a `main` (al fusionar el PR) | Solo valida: mismo código que develop e imagen existente | Reetiqueta la de develop: `latest` y `sha-<commit de main>` | Producción (entorno `production`) |
| `hotfix/*` → `main` | Como no hay imagen de develop, el pipeline completo | `sha-<commit>` y `latest` | Producción |

**Reglas que hace cumplir el pipeline** (el plan gratuito de GitHub no protege ramas en repos privados; el
trabajo `branches` falla y no despliega nada si no se cumplen):

- `develop` debe contener todo `main`. Después de un hotfix, fusiona `main` en `develop` antes de seguir.
- A `main` solo llegan PRs desde `develop` o `hotfix/*`.
- Fusiona `develop` → `main` con **merge commit** (no squash ni rebase): así `main` reconoce el commit probado
  (el segundo padre) y promueve su imagen. Con otro tipo de merge, corre el pipeline completo.
- El tablero fusiona con el método que el repo permite (squash si está habilitado). En este repo **desactiva
  «Allow squash merging»** (GitHub → Settings → General → Pull Requests) para que el tablero y GitHub usen merge commit.
- Después de cada fusión hecha por el tablero, los demás PR hacia la misma rama que quedaron en conflicto devuelven su
  tarjeta a Desarrollo (si esperaba en una puerta) o reciben un comentario (si se está trabajando).
- Fusiona también los PR a `develop` con **merge commit**: así `develop` encuentra la marca del PR y no repite sus
  pruebas. Los PR de Dependabot no pueden dejar la marca (su token es de solo lectura): `develop` los prueba de nuevo.

**Escaneos** (frenan solo lo grave; versiones fijadas por digest en `ci.yml`):

| Herramienta | Qué revisa | Frena si… |
|---|---|---|
| gitleaks | Secretos en todo el historial (excepciones en `.gitleaks.toml`) | Encuentra un secreto |
| Semgrep (`p/default`) | Patrones peligrosos en el código | Hay un hallazgo de severidad ERROR |
| Trivy (archivos) | Dependencias de la API (Maven), la web (pnpm) y el runner | Una falla crítica o alta con arreglo disponible |
| Trivy (imagen) | Sistema base y librerías de cada imagen | Una falla crítica o alta con arreglo disponible |
| Dependabot (`.github/dependabot.yml`) | Maven, pnpm, imágenes base y acciones | Nunca frena: abre PRs semanales hacia `develop` |

Las alertas de Dependabot y sus arreglos de seguridad se activan en **Settings → Code security**.

**Secretos de Dokploy**, uno por entorno (**Settings → Environments**), con los mismos nombres:

| Secreto | `staging` (pruebas) | `production` |
|---|---|---|
| `DOKPLOY_URL` | URL del panel de Dokploy | la misma |
| `DOKPLOY_API_KEY` | Dokploy → Settings → API keys | la misma |
| `DOKPLOY_API_APPLICATION_ID` | ID del servicio de la API de pruebas | ID del de producción |
| `DOKPLOY_WEB_APPLICATION_ID` | ID del servicio de la web de pruebas | ID del de producción |

Sin estos secretos el despliegue se salta con un aviso: la imagen queda publicada y se despliega con el
botón **Deploy** de Dokploy. Los servicios de pruebas usan las imágenes `:develop`, su propia base de datos y
sus propias variables; los de producción, `:latest`. En el entorno `production` conviene limitar
**Deployment branches** a `main` (y en `staging`, a `develop`) para que una rama con el workflow cambiado no
lea los secretos.

Las acciones del CI están fijadas por commit (`@<sha> # vN`) y Dependabot propone las actualizaciones.

**Volver atrás**: cambia la imagen a `:sha-<commit>` y despliega. Flyway solo avanza: revisa que la
versión anterior siga siendo compatible con la base.
