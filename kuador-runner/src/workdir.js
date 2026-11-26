import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, rmSync, statfsSync, statSync } from "node:fs";
import { join } from "node:path";

import { HOME, REPOS, WORK } from "./config.js";

const git = (cwd, ...args) => spawnSync("git", args, { cwd, encoding: "utf8", timeout: 120_000 });

/** Lo que una compilación deja y se puede volver a generar: se borra solo si git lo ignora (nunca código). */
export const BUILD_DIRS = new Set([
  "node_modules", "target", "build", "dist", "out", ".next", ".nuxt", ".turbo", ".svelte-kit", ".angular", ".parcel-cache",
  ".dart_tool", ".gradle", ".pub-cache", "coverage", ".pytest_cache", "__pycache__", ".venv", "venv", "bin", "obj", "Pods", "DerivedData",
]);

/** Días sin uso antes de borrar la carpeta de una tarjeta (si su trabajo ya está subido). */
const KEEP_DAYS = 2;
/** Espacio libre mínimo para tomar trabajo nuevo. */
const MIN_FREE_GB = 10;

/** ¿Es un repositorio git? */
export function isRepo(path) {
  return existsSync(path) && git(path, "rev-parse", "--is-inside-work-tree").stdout?.trim() === "true";
}

/** «KUADOR/kuador-medical», «https://github.com/KUADOR/kuador-medical.git» o «git@github.com:KUADOR/kuador-medical» → «kuador/kuador-medical». */
export function repoSlug(remote) {
  // En Windows un remoto local viene con barras invertidas (C:\repos\KUADOR\kuador-medical.git).
  const m = String(remote ?? "").trim().replaceAll("\\", "/").match(/([^/:\s]+)\/([^/\s]+?)(?:\.git)?\/?$/);
  return m ? `${m[1]}/${m[2]}`.toLowerCase() : null;
}

function origin(path) {
  return repoSlug(git(path, "remote", "get-url", "origin").stdout);
}

/**
 * El repo local de donde salen las copias de la tarjeta: el vinculado al tablero; si no, otro vinculado que apunta al
 * mismo repositorio (dos tableros sobre el mismo repo); si no, un clon propio del runner, uno solo por repositorio.
 * Así nunca hay un clon por tarjeta.
 *
 * Un tablero puede trabajar en varios proyectos, cada uno con su repositorio: ahí la carpeta vinculada al tablero vale
 * solo si es de uno de los repositorios de la tarjeta. Para decirlo a mano: `link <TABLERO>/<PROYECTO> <carpeta>`.
 */
export function sourceRepo(config, claim, log = () => {}) {
  const wanted = (claim.repositories ?? []).map(repoSlug).filter(Boolean);
  const exact = claim.subjectKey ? config.repos?.[`${claim.projectKey}/${claim.subjectKey}`] : null;
  if (exact && isRepo(exact)) return exact;
  const linked = config.repos?.[claim.projectKey];
  if (linked && isRepo(linked) && (!claim.manySubjects || wanted.length === 0 || wanted.includes(origin(linked)))) return linked;
  if (wanted.length === 0) return null;
  for (const path of Object.values(config.repos ?? {})) {
    if (isRepo(path) && wanted.includes(origin(path))) return path;
  }
  const slug = wanted[0];
  const dir = join(REPOS, slug.replace("/", "__"));
  if (isRepo(dir)) return dir;
  mkdirSync(REPOS, { recursive: true });
  log(`Clonando ${slug} una sola vez para las tarjetas de ${claim.projectKey}…`);
  const r = spawnSync("git", ["clone", "--quiet", `https://github.com/${slug}.git`, dir], { encoding: "utf8", timeout: 15 * 60_000 });
  if (r.status === 0) return dir;
  rmSync(dir, { recursive: true, force: true });
  log(`No se pudo clonar ${slug} (${(r.stderr || "").trim().slice(0, 160)}). Vincúlalo con: kuador-runner link ${claim.projectKey} <carpeta del repo>`);
  return null;
}

/**
 * Carpeta de trabajo de la tarjeta: una copia aparte del repo (git worktree) por tarjeta, para que varias trabajen en
 * paralelo sin pisarse las ramas. Comparte el historial con el repo de origen: no duplica el clon. Sin repo (ventas,
 * marketing...), una carpeta vacía.
 */
export function prepare(config, claim, log) {
  const dir = join(WORK, claim.issueKey);
  if (existsSync(dir) && isRepo(dir)) return { dir, repo: true };
  const repo = sourceRepo(config, claim, log);
  if (repo) {
    git(repo, "fetch", "--quiet", "--all", "--prune");
    git(repo, "worktree", "prune");
    const base = ["origin/develop", "origin/main", "origin/master", "HEAD"].find((ref) => git(repo, "rev-parse", "--verify", "--quiet", ref).status === 0);
    const r = git(repo, "worktree", "add", "--detach", dir, base ?? "HEAD");
    if (r.status === 0) return { dir, repo: true };
  }
  mkdirSync(dir, { recursive: true });
  return { dir, repo: false };
}

function size(path) {
  const r = spawnSync("du", ["-sk", path], { encoding: "utf8", timeout: 120_000 });
  if (r.status === 0) return Number(r.stdout.split(/\s/)[0]) * 1024;
  // Sin «du» (Windows): se suma a mano.
  let total = 0;
  const walk = (p) => {
    try {
      const st = statSync(p);
      if (!st.isDirectory()) return void (total += st.size);
      for (const n of readdirSync(p)) walk(join(p, n));
    } catch {
      // Un archivo que no se puede leer no cuenta.
    }
  };
  walk(path);
  return total;
}

/** Los repos (copias o clones) dentro de una carpeta de tarjeta: ella misma o un clon que el agente hizo adentro. */
function reposIn(dir) {
  if (isRepo(dir)) return [dir];
  try {
    return readdirSync(dir).map((n) => join(dir, n)).filter((p) => statSync(p).isDirectory() && existsSync(join(p, ".git")));
  } catch {
    return [];
  }
}

/**
 * Borra de una carpeta de tarjeta lo que la compilación deja (node_modules, build, target...), solo si git lo ignora.
 * El código y los cambios del agente no se tocan. Devuelve los bytes liberados.
 */
export function slim(dir) {
  let freed = 0;
  for (const repo of reposIn(dir)) {
    const walk = (path, depth) => {
      if (depth > 6) return;
      let entries;
      try {
        entries = readdirSync(path, { withFileTypes: true });
      } catch {
        return;
      }
      for (const e of entries) {
        if (!e.isDirectory() || e.name === ".git") continue;
        const full = join(path, e.name);
        if (BUILD_DIRS.has(e.name) && git(repo, "check-ignore", "-q", full).status === 0) {
          freed += size(full);
          rmSync(full, { recursive: true, force: true });
        } else if (!BUILD_DIRS.has(e.name)) {
          walk(full, depth + 1);
        }
      }
    };
    walk(repo, 0);
  }
  return freed;
}

/** ¿Lo que hay en este repo ya está a salvo? Sin cambios sin guardar y sin commits que no estén en el remoto. */
export function settled(repo) {
  const status = git(repo, "status", "--porcelain");
  if (status.status !== 0 || status.stdout.trim()) return false;
  const unpushed = git(repo, "log", "--oneline", "HEAD", "--not", "--remotes");
  return unpushed.status === 0 && !unpushed.stdout.trim();
}

/** Quita la carpeta de una tarjeta (y su copia registrada en el repo de origen). */
function remove(config, dir) {
  for (const source of [...Object.values(config.repos ?? {}), ...clones()]) {
    if (isRepo(source) && git(source, "worktree", "list").stdout.includes(dir)) git(source, "worktree", "remove", "--force", dir);
  }
  rmSync(dir, { recursive: true, force: true });
}

function clones() {
  try {
    return readdirSync(REPOS).map((n) => join(REPOS, n));
  } catch {
    return [];
  }
}

/**
 * Limpia las carpetas de las tarjetas que no están en curso: les quita lo que la compilación deja y borra las que llevan
 * más de {@code keepDays} sin uso y tienen su trabajo subido (las que tienen trabajo sin subir se quedan, adelgazadas).
 * Devuelve qué hizo, para el registro.
 */
export function cleanup(config, active = new Set(), { keepDays = config.keepDays ?? KEEP_DAYS, log = () => {} } = {}) {
  const report = { freed: 0, removed: [], kept: [] };
  if (!existsSync(WORK)) return report;
  const old = Date.now() - keepDays * 24 * 3600_000;
  for (const name of readdirSync(WORK)) {
    if (active.has(name) || name.startsWith("_")) continue;
    const dir = join(WORK, name);
    try {
      const idle = statSync(dir).mtimeMs <= old;
      const repos = reposIn(dir);
      if (idle && repos.every(settled)) {
        const bytes = size(dir);
        remove(config, dir);
        report.freed += bytes;
        report.removed.push(name);
        continue;
      }
      report.freed += slim(dir);
      if (idle) report.kept.push(name);
    } catch (e) {
      log(`${name}: no se pudo limpiar (${e.message}); se reintenta en la próxima.`);
    }
  }
  for (const source of [...Object.values(config.repos ?? {}), ...clones()]) if (isRepo(source)) git(source, "worktree", "prune");
  if (report.kept.length) log(`Sin borrar por tener trabajo sin subir: ${report.kept.join(", ")}`);
  return report;
}

/** Espacio libre (bytes) en el disco donde trabaja el runner. */
export function freeBytes() {
  try {
    const s = statfsSync(HOME);
    return s.bavail * s.bsize;
  } catch {
    return Infinity;
  }
}

export const minFree = (config) => (config.minFreeGb ?? MIN_FREE_GB) * 1024 ** 3;

/**
 * Docker después de cada tarjeta: las imágenes huérfanas (las que reemplazó un build nuevo) y la caché de construcción
 * de más de un día. No toca imágenes con nombre, contenedores ni volúmenes. Se apaga con «dockerCleanup: false».
 */
export function pruneDocker(config) {
  if (config.dockerCleanup === false) return Promise.resolve(false);
  const run = (args) =>
    new Promise((resolve) => {
      const child = spawn("docker", args, { stdio: "ignore" });
      const timer = setTimeout(() => child.kill(), 5 * 60_000);
      child.on("error", () => resolve(false));
      child.on("close", (code) => {
        clearTimeout(timer);
        resolve(code === 0);
      });
    });
  return run(["info"]).then((up) => (up ? run(["image", "prune", "-f"]).then(() => run(["builder", "prune", "-f", "--filter", "until=24h"])) : false));
}

export const gb = (bytes) => `${(bytes / 1024 ** 3).toFixed(1)} GB`;
