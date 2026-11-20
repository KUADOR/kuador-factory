import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

process.env.KUADOR_HOME = mkdtempSync(join(tmpdir(), "kuador-workdir-"));
const { WORK } = await import("../src/config.js");
const { cleanup, prepare, repoSlug, settled, slim, sourceRepo } = await import("../src/workdir.js");

const sh = (cwd, ...args) => {
  const r = spawnSync("git", args, { cwd, encoding: "utf8" });
  assert.equal(r.status, 0, r.stderr);
  return r.stdout.trim();
};

/** Un repo con su remoto (bare) y rama develop, como el de un tablero vinculado. */
function linkedRepo() {
  const root = mkdtempSync(join(tmpdir(), "kuador-repo-"));
  // El remoto se llama como el repositorio del tablero (KUADOR/kuador-medical).
  mkdirSync(join(root, "KUADOR"));
  const remote = join(root, "KUADOR", "kuador-medical.git");
  const repo = join(root, "local");
  sh(root, "init", "--quiet", "--bare", "-b", "develop", remote);
  sh(root, "clone", "--quiet", remote, repo);
  sh(repo, "config", "user.email", "a@b.c");
  sh(repo, "config", "user.name", "A");
  writeFileSync(join(repo, ".gitignore"), "node_modules/\nbuild/\n");
  writeFileSync(join(repo, "app.js"), "1");
  sh(repo, "add", ".");
  sh(repo, "commit", "--quiet", "-m", "base");
  sh(repo, "push", "--quiet", "origin", "HEAD:develop");
  return { repo, remote };
}

const old = (dir) => utimesSync(dir, new Date(Date.now() - 3 * 86_400_000), new Date(Date.now() - 3 * 86_400_000));

test("reconoce el mismo repositorio escrito de distintas formas", () => {
  assert.equal(repoSlug("KUADOR/kuador-medical"), "kuador/kuador-medical");
  assert.equal(repoSlug("https://github.com/KUADOR/kuador-medical.git"), "kuador/kuador-medical");
  assert.equal(repoSlug("git@github.com:KUADOR/kuador-medical.git"), "kuador/kuador-medical");
  assert.equal(repoSlug("C:\\Users\\ana\\KUADOR\\kuador-medical.git"), "kuador/kuador-medical");
  assert.equal(repoSlug(""), null);
});

test("un tablero sin repo vinculado usa el de otro tablero sobre el mismo repositorio: no clona", () => {
  const { repo } = linkedRepo();
  const config = { repos: { MED: repo } };
  assert.equal(sourceRepo(config, { projectKey: "MEDK", repositories: ["KUADOR/kuador-medical"] }), repo);
  assert.equal(sourceRepo(config, { projectKey: "VEN", repositories: [] }), null, "sin repositorio: carpeta vacía");
  const work = prepare(config, { issueKey: "MEDK-1", projectKey: "MEDK", repositories: ["KUADOR/kuador-medical"] });
  assert.equal(work.repo, true);
  assert.ok(sh(repo, "worktree", "list").includes("MEDK-1"), "es una copia liviana del repo vinculado");
});

test("al adelgazar borra solo la compilación que git ignora; el código y lo nuevo quedan", () => {
  const { repo } = linkedRepo();
  const work = prepare({ repos: { MED: repo } }, { issueKey: "MED-2", projectKey: "MED" });
  mkdirSync(join(work.dir, "node_modules", "left-pad"), { recursive: true });
  writeFileSync(join(work.dir, "node_modules", "left-pad", "index.js"), "x".repeat(10_000));
  mkdirSync(join(work.dir, "movil", "build"), { recursive: true });
  writeFileSync(join(work.dir, "movil", "build", "app.apk"), "x".repeat(10_000));
  // Una carpeta «dist» que el proyecto versiona (no está ignorada): es código, no se toca.
  mkdirSync(join(work.dir, "dist"));
  writeFileSync(join(work.dir, "dist", "kept.js"), "1");
  writeFileSync(join(work.dir, "nuevo.js"), "trabajo del agente");

  assert.ok(slim(work.dir) > 0);
  assert.equal(existsSync(join(work.dir, "node_modules")), false);
  assert.equal(existsSync(join(work.dir, "movil", "build")), false);
  assert.equal(existsSync(join(work.dir, "dist", "kept.js")), true);
  assert.equal(existsSync(join(work.dir, "nuevo.js")), true);
});

test("borra las carpetas viejas con el trabajo subido y conserva las que tienen trabajo sin subir", () => {
  const { repo } = linkedRepo();
  const config = { repos: { MED: repo } };
  const pushed = prepare(config, { issueKey: "MED-3", projectKey: "MED" }).dir;
  const pending = prepare(config, { issueKey: "MED-4", projectKey: "MED" }).dir;
  const running = prepare(config, { issueKey: "MED-5", projectKey: "MED" }).dir;
  writeFileSync(join(pending, "sin-subir.js"), "1");
  assert.equal(settled(pushed), true);
  assert.equal(settled(pending), false);
  for (const d of [pushed, pending, running]) old(d);

  const report = cleanup(config, new Set(["MED-5"]));
  assert.deepEqual(report.removed, ["MED-3"]);
  assert.equal(existsSync(pushed), false);
  assert.equal(sh(repo, "worktree", "list").includes("MED-3"), false, "la copia también sale del repo");
  assert.equal(existsSync(join(pending, "sin-subir.js")), true, "trabajo sin subir: se queda");
  assert.equal(existsSync(running), true, "en curso: no se toca");
  assert.ok(report.kept.includes("MED-4"));
  assert.ok(existsSync(WORK));
});
