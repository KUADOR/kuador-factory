import { spawnSync } from "node:child_process";
import { existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { HOME, WORK } from "./config.js";

/**
 * Lo que un agente deja fuera de su carpeta de trabajo: temporales, SDKs bajados a mano y procesos en segundo plano
 * (servidores de desarrollo, pruebas). Sin esto se acumulan gigas en /tmp y procesos vivos de tarjetas ya borradas.
 */

/** Temporales de cada tarjeta: fuera de la copia del repo (para que git no los vea) y se borran al terminar. */
export const TMP = join(HOME, "tmp");
/** SDKs y herramientas compartidas entre tarjetas (p. ej. flutter-3.50.1): se bajan una vez, no una por tarjeta. */
export const TOOLS = join(HOME, "tools");
/** Lo que apareció en /tmp mientras trabajaban los agentes, para barrerlo cuando lleve un día sin uso. */
const LEDGER = join(HOME, "tmp-ledger.json");
const SYSTEM_TMP = "/tmp";
const IDLE_MS = 24 * 3600_000;
/** De /tmp nunca se toca lo del propio Claude Code ni lo del sistema. */
const KEEP = /^(claude-|com\.apple|launch|\.)/;

/** Carpeta temporal de una tarjeta, creada si falta. */
export function jobTmp(issueKey) {
  const dir = join(TMP, issueKey);
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  mkdirSync(TOOLS, { recursive: true });
  return dir;
}

/** Variables para el agente: sus temporales en la carpeta de la tarjeta y dónde están las herramientas compartidas. */
export function jobEnv(issueKey) {
  const tmp = jobTmp(issueKey);
  return { TMPDIR: tmp, TMP: tmp, TEMP: tmp, KUADOR_TOOLS: TOOLS };
}

/** Las herramientas que ya hay en la carpeta compartida (nombre-versión). */
export function sharedTools() {
  try {
    return readdirSync(TOOLS, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name).sort();
  } catch {
    return [];
  }
}

/** Regla que el runner suma a las instrucciones del agente: depende de esta máquina, por eso no viene del tablero. */
export function hygieneRules(issueKey) {
  const tools = sharedTools();
  return [
    "Reglas de esta máquina (runner de Kuador):",
    `- Archivos temporales, registros, capturas y perfiles de navegador van en $TMPDIR (${join(TMP, issueKey)}), nunca en /tmp: se borran al terminar la tarjeta.`,
    `- Si necesitas un SDK o una herramienta que no está instalada o no tiene la versión pedida, búscala primero en ${TOOLS}${tools.length ? ` (hay: ${tools.join(", ")})` : ""}. Si no está, instálala ahí como <nombre>-<versión> (p. ej. flutter-3.50.1) para que la reutilicen las próximas tarjetas. No la bajes a otra carpeta.`,
    "- Detén los servidores y procesos que dejes en segundo plano antes de terminar.",
  ].join("\n");
}

// ── /tmp: lo que los agentes dejan aunque se les pida lo contrario ──

const systemTmp = () => (process.platform === "win32" || !existsSync(SYSTEM_TMP) ? null : SYSTEM_TMP);

/** Nombres que hay hoy en /tmp (vacío si no aplica). */
export function tmpSnapshot(root = systemTmp()) {
  if (!root) return new Set();
  try {
    return new Set(readdirSync(root));
  } catch {
    return new Set();
  }
}

const readLedger = () => {
  try {
    return JSON.parse(readFileSync(LEDGER, "utf8"));
  } catch {
    return {};
  }
};

/** Anota lo nuevo en /tmp desde {@code before} (solo lo que es de este usuario). */
export function recordTmp(before, root = systemTmp()) {
  if (!root) return [];
  const ledger = readLedger();
  const uid = process.getuid?.();
  const added = [];
  for (const name of tmpSnapshot(root)) {
    if (before.has(name) || KEEP.test(name) || ledger[name]) continue;
    try {
      if (uid !== undefined && lstatSync(join(root, name)).uid !== uid) continue;
    } catch {
      continue;
    }
    ledger[name] = Date.now();
    added.push(name);
  }
  if (added.length) writeFileSync(LEDGER, JSON.stringify(ledger));
  return added;
}

/** La fecha más reciente de cambio dentro de una ruta (hasta cierta profundidad), para saber si alguien la usa. */
function lastTouched(path, depth = 3) {
  let st;
  try {
    st = lstatSync(path);
  } catch {
    return 0;
  }
  let latest = st.mtimeMs;
  if (st.isDirectory() && depth > 0) {
    try {
      for (const n of readdirSync(path)) latest = Math.max(latest, lastTouched(join(path, n), depth - 1));
    } catch {
      // Sin permiso para leer: cuenta su propia fecha.
    }
  }
  return latest;
}

/** Borra de /tmp lo que dejaron los agentes y lleva un día sin uso ni procesos que lo usen. Devuelve lo borrado. */
export function sweepTmp(root = systemTmp(), { idleMs = IDLE_MS, now = Date.now(), running = commandLines } = {}) {
  if (!root) return [];
  const ledger = readLedger();
  const names = Object.keys(ledger);
  if (!names.length) return [];
  const busy = running().join("\n");
  const removed = [];
  for (const name of names) {
    const path = join(root, name);
    if (!existsSync(path)) {
      delete ledger[name];
      continue;
    }
    if (now - ledger[name] < idleMs || now - lastTouched(path) < idleMs || busy.includes(path)) continue;
    rmSync(path, { recursive: true, force: true });
    delete ledger[name];
    removed.push(name);
  }
  writeFileSync(LEDGER, JSON.stringify(ledger));
  return removed;
}

// ── Procesos que siguen vivos después de la tarjeta ──

/** «pid comando» de los procesos de este usuario (vacío en Windows). */
function processes() {
  if (process.platform === "win32") return [];
  const r = spawnSync("ps", ["-x", "-o", "pid=,command="], { encoding: "utf8", timeout: 15_000 });
  if (r.status !== 0) return [];
  return r.stdout
    .split("\n")
    .map((l) => l.trim().match(/^(\d+)\s+(.*)$/))
    .filter(Boolean)
    .map(([, pid, command]) => ({ pid: Number(pid), command }));
}

const commandLines = () => processes().map((p) => p.command);

/** Detiene (SIGTERM) los procesos de este usuario cuyo comando cumple {@code match}; nunca el propio runner. */
function stopWhere(match, { list = processes, kill = process.kill.bind(process) } = {}) {
  let stopped = 0;
  for (const { pid, command } of list()) {
    if (pid === process.pid || pid === process.ppid || !match(command)) continue;
    try {
      kill(pid, "SIGTERM");
      stopped++;
    } catch {
      // Ya terminó.
    }
  }
  return stopped;
}

/**
 * Detiene los procesos que quedaron trabajando en la carpeta de una tarjeta (un vite, un flutter_tester...): los que
 * mencionan esa carpeta o su temporal. Devuelve cuántos detuvo.
 */
export function stopLeftovers(dirs, options) {
  const paths = dirs.filter(Boolean);
  const inside = (command, dir) => command.includes(`${dir}/`) || command.endsWith(dir) || command.includes(`${dir} `);
  return stopWhere((command) => paths.some((dir) => inside(command, dir)), options);
}

/** Procesos que siguen vivos en carpetas de tarjetas que ya no están en curso (aunque la carpeta ya no exista). */
export function stopOrphans(active = new Set(), options) {
  const prefix = `${WORK}/`;
  return stopWhere((command) => {
    for (let i = command.indexOf(prefix); i >= 0; i = command.indexOf(prefix, i + 1)) {
      const key = command.slice(i + prefix.length).split(/[/\s]/)[0];
      if (key && !active.has(key)) return true;
    }
    return false;
  }, options);
}

/** Al terminar una tarjeta: se detienen sus procesos y se borra su temporal. */
export function finishJob(issueKey, workDir) {
  const tmp = join(TMP, issueKey);
  const stopped = stopLeftovers([workDir, tmp]);
  rmSync(tmp, { recursive: true, force: true });
  return stopped;
}

/** Temporales de tarjetas que ya no están en curso (p. ej. tras un corte del runner). */
export function sweepJobTmps(active = new Set()) {
  if (!existsSync(TMP)) return [];
  const removed = [];
  for (const name of readdirSync(TMP)) {
    if (active.has(name)) continue;
    rmSync(join(TMP, name), { recursive: true, force: true });
    removed.push(name);
  }
  return removed;
}
