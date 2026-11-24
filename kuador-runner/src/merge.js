import { spawnSync } from "node:child_process";
import { rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const git = (cwd, ...args) => spawnSync("git", args, { cwd, encoding: "utf8", timeout: 300_000, maxBuffer: 64 * 1024 ** 2 });

/** Documentación: texto que se puede unir conservando los dos lados sin romper nada que compile. */
export const isDoc = (path) => /\.(md|mdx|markdown|txt|rst|adoc)$/i.test(path);

/**
 * Actualiza la rama de un PR en conflicto sin IA, cuando se puede: trae la rama, une la rama base y, si lo único en
 * conflicto es documentación, conserva los dos lados de cada parte (merge «union»), hace commit y sube. Si hay
 * conflictos en código (o algo raro: cambios sin subir, archivos borrados de un lado), deja todo como estaba y
 * devuelve {@code handled: false} para que lo resuelva el cliente de IA.
 */
export function autoSync(dir, { branch, baseBranch }, log = () => {}) {
  const fail = (reason) => ({ handled: false, reason });
  if (!branch || !baseBranch) return fail("faltan las ramas");
  if (git(dir, "status", "--porcelain").stdout.trim()) return fail("hay cambios sin guardar en la carpeta");
  if (git(dir, "fetch", "--quiet", "origin", branch, baseBranch).status !== 0) return fail("no se pudo traer las ramas");
  // Commits locales de la rama que no están en GitHub: no se pisan.
  const local = git(dir, "rev-parse", "--verify", "--quiet", `refs/heads/${branch}`);
  if (local.status === 0 && git(dir, "merge-base", "--is-ancestor", branch, `origin/${branch}`).status !== 0) return fail("la rama tiene commits sin subir");
  if (git(dir, "checkout", "--quiet", "-B", branch, `origin/${branch}`).status !== 0) return fail("no se pudo cambiar a la rama");
  const merge = git(dir, "-c", "user.name=Kuador", "-c", "user.email=runner@kuador.local", "merge", "--no-edit", "--no-ff", `origin/${baseBranch}`);
  let files = [];
  if (merge.status !== 0) {
    files = git(dir, "diff", "--name-only", "--diff-filter=U").stdout.split("\n").filter(Boolean);
    const code = files.filter((f) => !isDoc(f));
    if (files.length === 0 || code.length > 0) {
      git(dir, "merge", "--abort");
      return fail(files.length === 0 ? "el merge falló sin conflictos" : `conflicto en código: ${code.slice(0, 5).join(", ")}`);
    }
    for (const f of files) {
      if (!union(dir, f)) {
        git(dir, "merge", "--abort");
        return fail(`no se pudo unir ${f}`);
      }
    }
    const commit = git(dir, "-c", "user.name=Kuador", "-c", "user.email=runner@kuador.local", "commit", "--quiet", "--no-edit");
    if (commit.status !== 0) {
      git(dir, "merge", "--abort");
      return fail("no se pudo hacer commit del merge");
    }
  }
  const push = git(dir, "push", "--quiet", "origin", `HEAD:${branch}`);
  if (push.status !== 0) return fail(`no se pudo subir: ${(push.stderr || "").trim().slice(0, 120)}`);
  log(files.length ? `Conflicto solo en documentación (${files.join(", ")}): resuelto sin IA conservando los dos lados.` : "La rama solo estaba atrasada: actualizada sin IA.");
  return { handled: true, files };
}

/** Une un archivo en conflicto conservando las líneas de los dos lados. Falla si uno de los lados lo borró. */
function union(dir, file) {
  const stage = (n) => {
    const r = git(dir, "show", `:${n}:${file}`);
    return r.status === 0 ? r.stdout : null;
  };
  const ours = stage(2);
  const theirs = stage(3);
  if (ours === null || theirs === null) return false;
  const base = stage(1) ?? "";
  const tmp = (name, text) => {
    const p = join(dir, ".git-union-" + name);
    writeFileSync(p, text);
    return p;
  };
  // En un worktree «.git» es un archivo: los temporales van junto al archivo y se borran enseguida.
  const paths = [tmp("ours", ours), tmp("base", base), tmp("theirs", theirs)];
  try {
    const r = git(dir, "merge-file", "--union", "-p", ...paths);
    if (r.status !== 0 || r.error) return false;
    writeFileSync(join(dir, file), r.stdout);
    return git(dir, "add", "--", file).status === 0;
  } finally {
    for (const p of paths) rmSync(p, { force: true });
  }
}
