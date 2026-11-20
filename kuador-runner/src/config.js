import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

/** Todo vive en ~/.kuador (o KUADOR_HOME): la configuración con la conexión (solo legible por ti), los registros y las carpetas de trabajo. */
export const HOME = process.env.KUADOR_HOME || join(homedir(), ".kuador");
export const CONFIG = join(HOME, "runner.json");
export const STATE = join(HOME, "state.json");
export const PAUSE = join(HOME, "paused");
export const LOGS = join(HOME, "logs");
export const WORK = join(HOME, "work");
/** Los clones que hace el runner cuando un tablero no tiene su repo vinculado: uno por repositorio, no por tarjeta. */
export const REPOS = join(HOME, "repos");
export const SECRETS = join(HOME, "mcp");
export const APP = join(HOME, "app");

export function ensureDirs() {
  for (const dir of [HOME, LOGS, WORK, SECRETS, APP]) mkdirSync(dir, { recursive: true, mode: 0o700 });
}

export function load() {
  if (!existsSync(CONFIG)) return null;
  return JSON.parse(readFileSync(CONFIG, "utf8"));
}

export function save(config) {
  ensureDirs();
  writeFileSync(CONFIG, JSON.stringify(config, null, 2), { mode: 0o600 });
  try {
    chmodSync(CONFIG, 0o600);
  } catch {
    // Windows: los permisos los da la carpeta del usuario.
  }
}

export function requireConfig() {
  const config = load();
  if (!config?.token || !config?.machineId) throw new Error("Esta máquina todavía no está conectada. Ejecuta: npx @kuador-npm/runner install");
  return config;
}

export function readState() {
  try {
    return JSON.parse(readFileSync(STATE, "utf8"));
  } catch {
    return null;
  }
}

export function writeState(state) {
  try {
    writeFileSync(STATE, JSON.stringify(state, null, 2));
  } catch {
    // El estado es solo para «status»: si no se puede escribir, el runner sigue.
  }
}
