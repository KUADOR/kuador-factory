# syntax=docker/dockerfile:1

FROM node:26-slim AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm build

FROM node:26-slim
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3600 HOSTNAME=0.0.0.0
# La web corre con node server.js: npm, npx y corepack no hacen falta en producción y traen sus propias fallas.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack
WORKDIR /app
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
# standalone no copia public/: sin esto no hay service worker (avisos push) ni íconos de la PWA.
COPY --from=build --chown=node:node /app/public ./public
USER node
EXPOSE 3600
# BOARD_API_URL, BOARD_PUBLIC_API_URL y PRIMEUI_LICENSE llegan por variables de entorno al arrancar.
CMD ["node", "server.js"]
