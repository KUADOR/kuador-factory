import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, rmSync, statSync } from "node:fs";
import { join } from "node:path";

import { WORK } from "./config.js";

const git = (cwd, ...args) => spawnSync("git", args, { cwd, encoding: "utf8", timeout: 120_000 });

/** ¿Es un repositorio git? */
export function isRepo(path) {
  return existsSync(path) && git(path, "rev-parse", "--is-inside-work-tree").stdout?.trim() === "true";
}

/**
 * Carpeta de trabajo de la tarjeta. Con el repo del tablero vinculado, una copia aparte (git worktree) por tarjeta: así
 * varias trabajan en paralelo sin pisarse las ramas. Sin repo (ventas, marketing...), una carpeta vacía.
 */
export function prepare(config, claim) {
  const dir = join(WORK, claim.issueKey);
  const repo = config.repos?.[claim.projectKey];
  if (repo && isRepo(repo)) {
    if (existsSync(dir) && isRepo(dir)) return { dir, repo: true };
    git(repo, "fetch", "--quiet", "--all", "--prune");
    git(repo, "worktree", "prune");
    const base = ["origin/develop", "origin/main", "origin/master", "HEAD"].find((ref) => git(repo, "rev-parse", "--verify", "--quiet", ref).status === 0);
    const r = git(repo, "worktree", "add", "--detach", dir, base ?? "HEAD");
    if (r.status === 0) return { dir, repo: true };
  }
  mkdirSync(dir, { recursive: true });
  return { dir, repo: false };
}

/** Borra las carpetas de tarjetas sin tocar hace más de una semana (las copias del repo, con git). */
export function cleanup(config, active) {
  if (!existsSync(WORK)) return;
  const old = Date.now() - 7 * 24 * 3600_000;
  for (const name of readdirSync(WORK)) {
    if (active.has(name)) continue;
    const dir = join(WORK, name);
    try {
      if (statSync(dir).mtimeMs > old) continue;
      const repo = Object.values(config.repos ?? {}).find((r) => isRepo(r) && git(r, "worktree", "list").stdout.includes(dir));
      if (repo) git(repo, "worktree", "remove", "--force", dir);
      rmSync(dir, { recursive: true, force: true });
    } catch {
      // Se reintenta en la próxima limpieza.
    }
  }
}
