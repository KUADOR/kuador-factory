import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { autoSync, isDoc } from "../src/merge.js";

const sh = (cwd, ...args) => {
  const r = spawnSync("git", args, { cwd, encoding: "utf8" });
  assert.equal(r.status, 0, r.stderr);
  return r.stdout.trim();
};

/** Remoto con develop y una rama de tarjeta; luego develop recibe otro PR que toca lo mismo. */
function conflicted(files) {
  const root = mkdtempSync(join(tmpdir(), "kuador-merge-"));
  const remote = join(root, "remote.git");
  const a = join(root, "a");
  sh(root, "init", "--quiet", "--bare", "-b", "develop", remote);
  sh(root, "clone", "--quiet", remote, a);
  sh(a, "config", "user.email", "a@b.c");
  sh(a, "config", "user.name", "A");
  for (const f of Object.keys(files)) writeFileSync(join(a, f), "# Título\n\nlínea base\n");
  sh(a, "add", ".");
  sh(a, "commit", "--quiet", "-m", "base");
  sh(a, "push", "--quiet", "origin", "HEAD:develop");
  sh(a, "checkout", "--quiet", "-b", "feature/KUA-2");
  for (const f of Object.keys(files)) writeFileSync(join(a, f), "# Título\n\nlínea de la tarjeta\n");
  sh(a, "commit", "--quiet", "-am", "tarjeta");
  sh(a, "push", "--quiet", "origin", "feature/KUA-2");
  sh(a, "checkout", "--quiet", "develop");
  for (const f of Object.keys(files)) writeFileSync(join(a, f), "# Título\n\nlínea de otro PR\n");
  sh(a, "commit", "--quiet", "-am", "otro PR");
  sh(a, "push", "--quiet", "origin", "develop");
  // La carpeta de la tarjeta: una copia aparte, como la del runner.
  const work = join(root, "work");
  sh(root, "clone", "--quiet", remote, work);
  sh(work, "config", "user.email", "r@b.c");
  sh(work, "config", "user.name", "R");
  return { remote, work };
}

test("reconoce la documentación", () => {
  assert.ok(isDoc("docs/architecture.md") && isDoc("README.md") && isDoc("AGENTS.MD"));
  assert.ok(!isDoc("api/pom.xml") && !isDoc(".github/workflows/ci.yml") && !isDoc("src/App.tsx"));
});

test("un conflicto solo en documentación se resuelve sin IA conservando los dos lados", () => {
  const { remote, work } = conflicted({ "README.md": 1, "AGENTS.md": 1 });
  const r = autoSync(work, { branch: "feature/KUA-2", baseBranch: "develop" });
  assert.equal(r.handled, true, r.reason);
  assert.deepEqual(r.files.sort(), ["AGENTS.md", "README.md"]);
  const readme = sh(remote, "show", "feature/KUA-2:README.md");
  assert.match(readme, /línea de la tarjeta/);
  assert.match(readme, /línea de otro PR/);
  assert.doesNotMatch(readme, /<<<<<<<|>>>>>>>/);
  assert.equal(sh(remote, "merge-base", "--is-ancestor", "develop", "feature/KUA-2"), "", "la rama ya contiene develop");
});

test("un conflicto en código queda para la IA y no se sube nada", () => {
  const { remote, work } = conflicted({ "README.md": 1, "App.java": 1 });
  const before = sh(remote, "rev-parse", "feature/KUA-2");
  const r = autoSync(work, { branch: "feature/KUA-2", baseBranch: "develop" });
  assert.equal(r.handled, false);
  assert.match(r.reason, /App\.java/);
  assert.equal(sh(remote, "rev-parse", "feature/KUA-2"), before);
  assert.equal(sh(work, "status", "--porcelain"), "", "la carpeta queda limpia");
  // En Windows git escribe los saltos de línea como \r\n.
  assert.equal(readFileSync(join(work, "App.java"), "utf8").replaceAll("\r\n", "\n"), "# Título\n\nlínea de la tarjeta\n");
});
