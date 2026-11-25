#!/usr/bin/env bash
# Levanta lo que las pruebas de punta a punta necesitan —una base desechable, la API y la web, en puertos propios— y
# corre Playwright contra eso. Al terminar lo apaga todo. Es lo mismo en una PC y en el CI.
#
#   e2e/entorno.sh                 corre todas las pruebas
#   e2e/entorno.sh tablero         solo las que coinciden
#   E2E_KEEP=1 e2e/entorno.sh      deja el entorno arriba al terminar (para mirar en el navegador)
#   E2E_CHANNEL=chrome             usa el Chrome instalado en vez de descargar Chromium
set -euo pipefail

WEB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
API_DIR="$WEB_DIR/../kuador-board-api"
WORK="${E2E_WORK:-$(mktemp -d)}"
# El puerto de la base se puede cambiar si otro proyecto ya usa el 55432.
PG_PORT=${E2E_PG_PORT:-55432}; API_PORT=18091; WEB_PORT=13600
PG_NAME=kuador-e2e-pg

export E2E_BASE_URL="http://127.0.0.1:$WEB_PORT"
export E2E_API_URL="http://127.0.0.1:$API_PORT"
export E2E_EMAIL="prueba@e2e.test"
export E2E_PASSWORD="clave-larga-de-prueba-1"

# Lo que escuche en un puerto del entorno (también lo que haya quedado de una corrida anterior).
liberar() { for port in "$@"; do lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null || true; done; }

apagar() {
  [ "${E2E_KEEP:-}" = 1 ] && { echo "Entorno arriba: $E2E_BASE_URL ($E2E_EMAIL / $E2E_PASSWORD). Registros en $WORK"; return; }
  liberar "$WEB_PORT" "$API_PORT"
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
}
trap apagar EXIT
liberar "$WEB_PORT" "$API_PORT"

esperar() { # url, nombre, registro
  for _ in $(seq 1 90); do curl -fsS -o /dev/null -m 2 "$1" 2>/dev/null && return 0; sleep 1; done
  echo "::error::$2 no respondió en $1"; tail -40 "$3" || true; exit 1
}

docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
docker run -d --name "$PG_NAME" -e POSTGRES_USER=kuador -e POSTGRES_PASSWORD=kuador -e POSTGRES_DB=kuador_board \
  -p "127.0.0.1:$PG_PORT:5432" postgres:18-alpine >/dev/null

JAR="$(ls "$API_DIR"/target/kuador-board-api-*.jar 2>/dev/null | grep -v original | head -1 || true)"
if [ -z "$JAR" ] || [ "${E2E_BUILD:-}" = 1 ]; then
  (cd "$API_DIR" && ./mvnw -B -q -DskipTests package)
  JAR="$(ls "$API_DIR"/target/kuador-board-api-*.jar | grep -v original | head -1)"
fi
[ -d "$WEB_DIR/.next" ] && [ "${E2E_BUILD:-}" != 1 ] || (cd "$WEB_DIR" && pnpm build >/dev/null)

# Desde otra carpeta: la API no lee el .env de quien desarrolla.
(cd "$WORK" && SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/kuador_board" SPRING_DATASOURCE_USERNAME=kuador \
  SPRING_DATASOURCE_PASSWORD=kuador PORT="$API_PORT" BOARD_BOOTSTRAP_ADMIN_EMAIL="$E2E_EMAIL" BOARD_BOOTSTRAP_ADMIN_PASSWORD="$E2E_PASSWORD" \
  BOARD_SECRETS_KEY="$(openssl rand -base64 32)" BOARD_WEB_URL="$E2E_BASE_URL" \
  exec java -jar "$JAR" >"$WORK/api.log" 2>&1) &
esperar "$E2E_API_URL/readyz" "La API" "$WORK/api.log"

(cd "$WEB_DIR" && BOARD_API_URL="$E2E_API_URL" exec node_modules/.bin/next start -p "$WEB_PORT" >"$WORK/web.log" 2>&1) &
esperar "$E2E_BASE_URL/login" "La web" "$WORK/web.log"

cd "$WEB_DIR"
pnpm exec playwright test "$@"
