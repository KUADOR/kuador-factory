import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { HOME } from "./config.js";

/**
 * Las conversaciones de Claude Code por tarjeta y rol, en esta máquina. Si la tarjeta vuelve al mismo rol (QA la
 * devolvió, una persona pidió cambios), se retoma esa conversación: el agente ya tiene el contexto y solo mira lo nuevo.
 * Claude Code guarda cada sesión por carpeta, así que solo se retoma en la misma carpeta de trabajo.
 */
const FILE = () => join(HOME, "sessions.json");
const MAX_AGE_MS = 7 * 24 * 3600_000;

const key = (claim) => `${claim.issueKey}|${claim.role}`;

function readAll() {
  try {
    return JSON.parse(readFileSync(FILE(), "utf8"));
  } catch {
    return {};
  }
}

/** La sesión para retomar (si existe, es reciente y su carpeta sigue ahí), o null. */
export function resumable(claim, cwd) {
  const s = readAll()[key(claim)];
  if (!s?.id || s.cwd !== cwd || !existsSync(cwd)) return null;
  if (Date.now() - new Date(s.at).getTime() > MAX_AGE_MS) return null;
  return s.id;
}

export function remember(claim, cwd, id) {
  if (!id) return;
  const all = readAll();
  all[key(claim)] = { id, cwd, at: new Date().toISOString() };
  // Sin crecer para siempre: se quedan las de la última semana.
  for (const [k, v] of Object.entries(all)) if (Date.now() - new Date(v.at).getTime() > MAX_AGE_MS) delete all[k];
  try {
    writeFileSync(FILE(), JSON.stringify(all, null, 2), { mode: 0o600 });
  } catch {
    // Sin archivo: la próxima vuelta empieza de cero, como antes.
  }
}

export function forget(claim) {
  const all = readAll();
  if (!(key(claim) in all)) return;
  delete all[key(claim)];
  try {
    writeFileSync(FILE(), JSON.stringify(all, null, 2), { mode: 0o600 });
  } catch {
    // Nada que hacer.
  }
}
