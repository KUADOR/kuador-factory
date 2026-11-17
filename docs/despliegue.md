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

## 6. Despliegues

Cada push a `main` corre las pruebas, publica las dos imágenes y, si existen estos secretos en
GitHub (**Settings → Secrets and variables → Actions**), pide a Dokploy que redespliegue:

| Secreto | Dónde se obtiene |
|---|---|
| `DOKPLOY_URL` | URL del panel de Dokploy |
| `DOKPLOY_API_KEY` | Dokploy → Settings → API keys |
| `DOKPLOY_API_APPLICATION_ID` | ID del servicio de la API (en la URL del servicio) |
| `DOKPLOY_WEB_APPLICATION_ID` | ID del servicio de la web |

**Protege la clave de Dokploy** (da control de todo el servidor): el trabajo `deploy` corre en el entorno
`production`. En GitHub → **Settings → Environments → production** (se crea solo en el primer despliegue):

1. **Deployment branches and tags** → *Selected branches* → `main`. Así una rama con el workflow cambiado
   no puede leer los secretos.
2. Mueve `DOKPLOY_URL`, `DOKPLOY_API_KEY` y los dos IDs de *Repository secrets* a los secretos de ese entorno.
3. Opcional: *Required reviewers* para aprobar cada despliegue.

Las acciones del CI están fijadas por commit (`@<sha> # vN`): para actualizarlas, cambia el sha por el del
nuevo tag (Dependabot puede hacerlo).

**Volver atrás**: cambia la imagen a `:sha-<commit>` y despliega. Flyway solo avanza: revisa que la
versión anterior siga siendo compatible con la base.
