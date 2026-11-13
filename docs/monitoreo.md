# Monitoreo

Todo corre en el mismo servidor de Dokploy, en un servicio **Compose** aparte
(`infra/monitoring/compose.dokploy.yaml`). Usa en total entre 0,8 y 1,5 GB de RAM.

```
            ┌───────────────────────── servidor ─────────────────────────┐
Internet ◀──┤ Alloy ── sondas cada 30 s ──▶ go.kuador.com, go-api…/readyz │
            │   │  ── métricas (red interna) ──▶ API :9464              │
            │   │  ── logs de todos los contenedores (socket de Docker)  │
            │   │  ── CPU, memoria y disco del servidor                  │
            │   ├──▶ Prometheus (métricas, 30 días, máx. 3 GB)           │
            │   └──▶ Loki (logs, 14 días)                                │
            │ Grafana ◀── monitoreo.kuador.com (único con dominio)       │
            └────────────────────────────────────────────────────────────┘
```

- **Tablero «Kuador Go»** (carpeta Kuador): disponibilidad de la web y la API, vencimiento del certificado,
  peticiones, errores 5xx, tiempos de respuesta, rutas lentas, memoria y CPU de la API, conexiones a la base,
  trabajos y tokens de los agentes, CPU/memoria/disco del servidor y los logs con filtro por servicio,
  nivel y texto.
- **Alertas por correo** (`grafana/provisioning/alerting/rules.yaml`):

  | Alerta | Cuándo |
  |---|---|
  | La web no responde / La API no responde | 2 min sin responder desde Internet |
  | La API no entrega métricas | 3 min sin poder leerla por la red interna |
  | Certificado HTTPS por vencer | menos de 14 días |
  | Errores 5xx en la API | más de 5 en 5 min |
  | Errores en los logs | cualquier línea ERROR (de cualquier servicio) |
  | Agentes fallando | 3 o más trabajos fallidos en 30 min |
  | Conexiones a la base saturadas | peticiones esperando conexión durante 5 min |
  | Disco casi lleno / Memoria del servidor baja | más del 85% / menos del 10% libre, 10 min |
  | La API casi sin memoria | más del 90% de su memoria, 10 min |

  Las alertas, el tablero y las fuentes de datos se cargan desde el repositorio: se cambian aquí y se
  redespliega, no en la interfaz.

  **Ojo**: Grafana lee estos archivos solo al arrancar, y el redeploy no recrea su contenedor si el compose no
  cambió. Después de cambiar algo en `grafana/`, reinicia el servicio `grafana` en Dokploy o recarga sin
  reiniciar:

  ```bash
  curl -X POST -u admin:'<contraseña>' https://monitoreo.kuador.com/api/admin/provisioning/alerting/reload
  curl -X POST -u admin:'<contraseña>' https://monitoreo.kuador.com/api/admin/provisioning/dashboards/reload
  ```

**Límite**: como todo vive en el mismo servidor, si el servidor entero se apaga no llega ningún aviso. Cuando
haga falta, se suma un chequeo externo gratuito (UptimeRobot, Better Stack…) contra `https://go-api.kuador.com/readyz`.

## Puesta en marcha

1. **DNS**: registro `A` `monitoreo` → IP del servidor.
2. **API** (servicio de la API en Dokploy → Environment), y redesplegar:

   ```dotenv
   MANAGEMENT_SERVER_PORT=9464
   LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
   ```

   El puerto 9464 no se agrega en *Domains*: solo lo alcanza la red interna. La salud pública queda en
   `https://go-api.kuador.com/readyz`.
3. **Compose**: en el proyecto, *Create Service → Compose*, proveedor GitHub, este repositorio, rama `main`,
   *Compose Path* `./infra/monitoring/compose.dokploy.yaml`. En *Environment*:

   ```dotenv
   # openssl rand -base64 24
   GRAFANA_ADMIN_PASSWORD=
   GRAFANA_URL=https://monitoreo.kuador.com
   # El «App Name» del servicio de la API en Dokploy (pestaña General), con el puerto de métricas.
   API_METRICS=<app-name-de-la-api>:9464
   WEB_URL=https://go.kuador.com
   API_URL=https://go-api.kuador.com
   # Correo que envía las alertas (puede ser la misma cuenta de Hostinger de la API).
   SMTP_HOST=smtp.hostinger.com:465
   SMTP_USER=
   SMTP_PASSWORD=
   # A quién le llegan: correos separados por punto y coma.
   ALERT_EMAILS=
   ```

4. **Domains** del Compose: `monitoreo.kuador.com`, servicio `grafana`, puerto `3000`, HTTPS.
5. **Deploy**. Entra con `admin` y la contraseña. En *Alerting → Contact points → Correo → Test* confirma que
   llega el correo de prueba.

## Seguridad

- Solo Grafana tiene dominio, con usuario y contraseña y sin registro abierto. Prometheus y Loki no se ven ni
  desde Internet ni desde los demás servicios del servidor (red `monitoreo` propia).
- Las métricas de la API van por un puerto aparte que Traefik no publica; desde el puerto público
  `/actuator/prometheus` pide sesión.
- Alloy monta el socket de Docker (para leer los logs y los nombres de los contenedores) y `/proc`, `/sys` y `/`
  en solo lectura. El socket da control total del servidor: no cambies la imagen de Alloy por una que no sea
  la oficial.
- Las métricas no llevan datos de los espacios (ni nombres, ni tarjetas, ni correos). Los logs pueden llevar
  correos de personas (p. ej. «no se pudo enviar el correo a …») y se guardan 14 días.

## Cuando crezca

- Más retención: `METRICS_RETENTION` (por defecto `30d`) y `retention_period` en `loki.yaml`.
- Base de datos por dentro: sumar `postgres-exporter`.
- Alertas por Telegram: *Alerting → Contact points* (o agregarlo en `contact-points.yaml`).
- Otro servidor o Grafana Cloud: Alloy puede enviar a cualquier Prometheus/Loki cambiando las dos URLs de
  `config.alloy`.
