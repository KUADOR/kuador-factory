# Garage: adjuntos de las tarjetas

Los adjuntos (propuestas, contratos, capturas) viven en **Garage**, un almacenamiento compatible con
S3, en la red interna del servidor. El API habla con Garage; el navegador nunca: las descargas pasan
por la web y el API con la sesión de cada persona. Garage no se publica a Internet.

Sin Garage el tablero funciona igual: la pestaña *Adjuntos* dice que no están habilitados.

Archivos en el repositorio (`infra/garage/`):

| Archivo | Para qué |
|---|---|
| `garage.toml` | Configuración de un nodo (sqlite, región `garage`, S3 en el puerto 3900). Los secretos llegan por variables. |
| `compose.dokploy.yaml` | Servicio de producción en Dokploy, sin puertos publicados, en `dokploy-network` con el alias `garage`. |
| `compose.yaml` | Lo mismo para desarrollo local, con el puerto 3900 solo en `127.0.0.1`. |
| `setup.sh` | Preparación de una sola vez: nodo, bucket y clave. Imprime las variables del API. |
| `.env.example` | Plantilla de los dos secretos de Garage para local. |

## Producción (Dokploy), paso a paso

Así se instaló en el VPS de Dokploy.

### 1. Crear el servicio de Garage

1. En Dokploy: *Create Service → Compose*, desde este repositorio (rama `main`), con
   **Compose Path** `infra/garage/compose.dokploy.yaml`.
2. En *Environment* del servicio, dos secretos generados en tu máquina (no van al repositorio ni a
   ningún chat):
   ```
   GARAGE_RPC_SECRET=    # openssl rand -hex 32
   GARAGE_ADMIN_TOKEN=   # openssl rand -base64 32
   ```
3. *Deploy*. No lleva dominio ni puertos.

### 2. Preparar Garage (una sola vez, por SSH)

Garage arranca vacío: hay que asignarle el nodo, crear el bucket y una clave. Lo hace `setup.sh`.

1. Entra por SSH al VPS (`ssh root@<ip-del-vps>`).
2. Comprueba que el contenedor está corriendo y anota su nombre:
   ```
   docker ps --filter ancestor=dxflrs/garage:v2.5.0 --format '{{.Names}}'
   ```
   Si no muestra nada, el servicio no está desplegado o no arrancó: revísalo en Dokploy.
3. Lleva el script al servidor. El VPS no tiene el repositorio, así que hay que crearlo ahí (si no,
   sale `-bash: ./setup.sh: No such file or directory`). Cualquiera de las dos formas:
   - Desde tu máquina: `scp infra/garage/setup.sh root@<ip-del-vps>:~/setup.sh`
   - En el servidor: `cat > setup.sh <<'EOF'`, pega el contenido de `infra/garage/setup.sh`, una
     línea con `EOF` y Enter.

   Luego: `chmod +x setup.sh`
4. Ejecútalo apuntando al contenedor:
   ```
   CONTAINER=$(docker ps --filter ancestor=dxflrs/garage:v2.5.0 --format '{{.Names}}' | head -1) ./setup.sh
   ```
   Al terminar imprime:
   ```
   Listo. Variables para el API (guárdalas como secretos, no en el repo):
   BOARD_STORAGE_S3_ENDPOINT=http://garage:3900
   BOARD_STORAGE_S3_REGION=garage
   BOARD_STORAGE_S3_BUCKET=kuador-adjuntos
   BOARD_STORAGE_S3_ACCESS_KEY=GK…
   BOARD_STORAGE_S3_SECRET_KEY=…
   ```
   Se puede volver a correr cuando quieras: no crea nada nuevo y muestra la misma clave.

### 3. Conectar el API

1. En Dokploy, servicio del **API** → *Environment*: pega las cinco variables `BOARD_STORAGE_S3_*`
   con los valores que imprimió el script. Opcional: `BOARD_STORAGE_MAX_FILE_SIZE=25MB`.
2. Redespliega el API (y la web, si no tiene la versión con la pestaña *Adjuntos*).
3. En el log del API, al arrancar, debe aparecer:
   ```
   Adjuntos en el bucket kuador-adjuntos
   ```
4. Prueba: abre una tarjeta → *Adjuntos* → sube un archivo → descárgalo.

## Problemas conocidos

| Síntoma | Causa y solución |
|---|---|
| `./setup.sh: No such file or directory` | El script no está en el servidor: créalo (paso 2.3). |
| `setup.sh` falla con `docker compose` | `CONTAINER` quedó vacío porque no hay contenedor de Garage corriendo (paso 2.2). |
| Log del API: `Adjuntos deshabilitados` | Faltan `BOARD_STORAGE_S3_ENDPOINT` o las claves en el API. |
| Log del API: `No se pudo acceder al bucket de adjuntos …` | El API no alcanza `garage`. Usa `http://<nombre-del-contenedor>:3900` en el endpoint, o revisa que ambos estén en la misma red: `docker network inspect dokploy-network \| grep -E 'garage\|api'`. |
| La pestaña dice que los adjuntos no están habilitados | El API arrancó sin las variables: agrégalas y redespliega. |

## Local

```
cd infra/garage
cp .env.example .env        # y completa los dos secretos (openssl, como arriba)
docker compose up -d
./setup.sh
```

En el `.env` del API pon las variables que imprime, pero con
`BOARD_STORAGE_S3_ENDPOINT=http://127.0.0.1:3900`.

## Respaldo y mantenimiento

- **Respaldo**: los volúmenes `garage-meta` (índice) y `garage-data` (archivos) del servicio. Respalda
  los dos juntos.
- **Rotar la clave del API**: `docker exec <contenedor> /garage key create kuador-api-2`, darle acceso
  con `docker exec <contenedor> /garage bucket allow --read --write --owner kuador-adjuntos --key kuador-api-2`,
  ver su secreto con `docker exec <contenedor> /garage key info --show-secret kuador-api-2`, cambiar las
  variables del API, redesplegar y borrar la anterior con `docker exec <contenedor> /garage key delete --yes kuador-api`.
- **Capacidad**: el script asigna 20 GB (`CAPACITY=50G ./setup.sh` en la primera corrida para otro
  valor). Ver el uso: `docker exec <contenedor> /garage bucket info kuador-adjuntos`.
- **Versión**: la imagen está fijada a `dxflrs/garage:v2.5.0` en los compose; súbela en ambos
  archivos a la vez.
