#!/bin/sh
# Prepara Garage una sola vez: asigna el nodo, crea el bucket y una clave con acceso a él.
# Al final imprime las variables que van en el API (BOARD_STORAGE_S3_*). Se puede volver a correr sin romper nada.
#
#   En local (junto a compose.yaml):      ./setup.sh
#   En el servidor (contenedor ya creado): CONTAINER=<nombre del contenedor de garage> ./setup.sh
#     (el nombre sale de `docker ps --filter ancestor=dxflrs/garage:v2.5.0 --format '{{.Names}}'`)
set -eu
SERVICE="${1:-garage}"
BUCKET="${BUCKET:-kuador-adjuntos}"
KEY_NAME="${KEY_NAME:-kuador-api}"
CAPACITY="${CAPACITY:-20G}"
if [ -n "${CONTAINER:-}" ]; then
  g() { docker exec "$CONTAINER" /garage "$@" 2>/dev/null; }
else
  g() { docker compose exec -T "$SERVICE" /garage "$@" 2>/dev/null; }
fi

NODE=$(g node id -q | cut -d@ -f1)
if ! g layout show | grep -q "$(echo "$NODE" | cut -c1-16)"; then
  g layout assign -z dc1 -c "$CAPACITY" "$NODE" >/dev/null
  VERSION=$(g layout show | sed -n 's/.*apply --version \([0-9]*\).*/\1/p' | head -1)
  g layout apply --version "${VERSION:-1}" >/dev/null
fi

g bucket info "$BUCKET" >/dev/null || g bucket create "$BUCKET" >/dev/null
if ! g key info "$KEY_NAME" >/dev/null; then
  g key create "$KEY_NAME" >/dev/null
fi
g bucket allow --read --write --owner "$BUCKET" --key "$KEY_NAME" >/dev/null

INFO=$(g key info --show-secret "$KEY_NAME")
ID=$(echo "$INFO" | sed -n 's/^Key ID: *//p')
SECRET=$(echo "$INFO" | sed -n 's/^Secret key: *//p')
cat <<OUT

Listo. Variables para el API (guárdalas como secretos, no en el repo).
En producción el API llega por la red interna (http://garage:3900); en local, http://127.0.0.1:3900.
BOARD_STORAGE_S3_ENDPOINT=http://garage:3900
BOARD_STORAGE_S3_REGION=garage
BOARD_STORAGE_S3_BUCKET=$BUCKET
BOARD_STORAGE_S3_ACCESS_KEY=$ID
BOARD_STORAGE_S3_SECRET_KEY=$SECRET
OUT
