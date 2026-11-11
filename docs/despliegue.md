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
```

- **Por el dominio de la API solo entran** el MCP de los clientes de IA de cada persona
  (`/mcp`), su inicio de sesión OAuth (`/.well-known/oauth-protected-resource[/mcp]`,
  `/.well-known/oauth-authorization-server`, `/oauth/register`, `/oauth/token`; la pantalla de permiso
  es `https://go.kuador.com/oauth/authorize`, por eso `BOARD_WEB_URL` debe ser la URL pública de la web),
  el kit de agentes (`/api/agent-kit`, `/api/agent-kit.zip`), el webhook de GitHub
  (`/api/github/webhook`) y `/actuator/health`. Lo hace la propia API (`BOARD_PUBLIC_HOST_GATE=true`):
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

# Primera cuenta (solo si la base está vacía). Cámbiala después de entrar.
BOARD_BOOTSTRAP_ADMIN_EMAIL=
BOARD_BOOTSTRAP_ADMIN_PASSWORD=

# Correo de los códigos del alta (SMTP). Sin SMTP nadie puede crear espacios nuevos.
SPRING_MAIL_HOST=smtp.hostinger.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=
SPRING_MAIL_PASSWORD=
BOARD_MAIL_FROM=Kuador Factory <no-reply@kuador.com>
BOARD_MAIL_LOG_CODES=false

# En el servidor no hay carpetas locales que sincronizar.
BOARD_SYNC_ALLOWED_ROOTS=

# Documentación de la API apagada en producción.
SPRINGDOC_API_DOCS_ENABLED=false
SPRINGDOC_SWAGGER_UI_ENABLED=false
```

Sin `ANTHROPIC_API_KEY`: los agentes corren en el Claude Code (o Codex, u otro cliente) de cada
persona, con su propia suscripción, conectados al MCP.

## 4. Servicio de la web

**Application**, imagen `ghcr.io/<dueño>/kuador-board-web:latest`. Dominio `go.kuador.com`,
path `/`, puerto `3600`, HTTPS.

```dotenv
BOARD_API_URL=http://<app-name-de-la-api>:8091
BOARD_PUBLIC_API_URL=https://go-api.kuador.com
PRIMEUI_LICENSE=
```

## 5. GitHub App

Con el tablero ya en su dominio, entra como dueña del espacio que opera la plataforma y ve a
**Conexiones → Registrar la app en GitHub**. El manifiesto lleva las URLs públicas, así que GitHub
vuelve al tablero desplegado. Una app registrada desde `localhost` queda con URLs locales: no se
reutiliza en producción.

## 6. Despliegues

Cada push a `main` corre las pruebas, publica las dos imágenes y, si existen estos secretos en
GitHub (**Settings → Secrets and variables → Actions**), pide a Dokploy que redespliegue:

| Secreto | Dónde se obtiene |
|---|---|
| `DOKPLOY_URL` | URL del panel de Dokploy |
| `DOKPLOY_API_KEY` | Dokploy → Settings → API keys |
| `DOKPLOY_API_APPLICATION_ID` | ID del servicio de la API (en la URL del servicio) |
| `DOKPLOY_WEB_APPLICATION_ID` | ID del servicio de la web |

**Volver atrás**: cambia la imagen a `:sha-<commit>` y despliega. Flyway solo avanza: revisa que la
versión anterior siga siendo compatible con la base.
