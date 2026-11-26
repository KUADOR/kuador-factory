import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

process.env.KUADOR_HOME = mkdtempSync(join(tmpdir(), "kuador-hygiene-"));
const { WORK } = await import("../src/config.js");
const { TOOLS, finishJob, hygieneRules, jobEnv, recordTmp, stopLeftovers, stopOrphans, sweepJobTmps, sweepTmp, tmpSnapshot } = await import("../src/hygiene.js");

const aged = (path, days) => utimesSync(path, new Date(Date.now() - days * 86_400_000), new Date(Date.now() - days * 86_400_000));

test("el agente recibe su propio temporal y la carpeta de herramientas compartidas", () => {
  const env = jobEnv("MED-1");
  assert.ok(env.TMPDIR.endsWith(join("tmp", "MED-1")));
  assert.equal(env.TMP, env.TMPDIR);
  assert.equal(env.KUADOR_TOOLS, TOOLS);
  assert.ok(existsSync(env.TMPDIR));
  assert.ok(!env.TMPDIR.startsWith(WORK), "fuera de la copia del repo, para que git no lo vea");
});

test("las reglas nombran las herramientas que ya están, para no bajarlas de nuevo", () => {
  mkdirSync(join(TOOLS, "flutter-3.50.1"), { recursive: true });
  const rules = hygieneRules("MED-1");
  assert.match(rules, /flutter-3\.50\.1/);
  assert.match(rules, /nunca en \/tmp/);
});

test("al terminar se borra el temporal de la tarjeta", () => {
  const { TMPDIR } = jobEnv("MED-2");
  writeFileSync(join(TMPDIR, "captura.png"), "x");
  finishJob("MED-2", join(WORK, "MED-2"));
  assert.ok(!existsSync(TMPDIR));
});

test("detiene solo los procesos que trabajan en la carpeta de la tarjeta", () => {
  const dir = join(WORK, "MED-3");
  const list = () => [
    { pid: 101, command: `node ${dir}/app/node_modules/.bin/vite --port 5183` },
    { pid: 102, command: `node ${join(WORK, "MED-30")}/app/vite` },
    { pid: 103, command: "node /otro/proyecto/vite" },
  ];
  const killed = [];
  assert.equal(stopLeftovers([dir], { list, kill: (pid) => killed.push(pid) }), 1);
  assert.deepEqual(killed, [101]);
});

test("detiene los procesos de tarjetas que ya no están en curso, no los de las activas", () => {
  const list = () => [
    { pid: 201, command: `node ${WORK}/PLATODO-18/app/vite` },
    { pid: 202, command: `flutter_tester --packages=${WORK}/MEDK-45/movil/.dart_tool/package_config.json` },
    { pid: 203, command: `node ${WORK}/PLATODO-58/app/vite` },
  ];
  const killed = [];
  stopOrphans(new Set(["PLATODO-58"]), { list, kill: (pid) => killed.push(pid) });
  assert.deepEqual(killed, [201, 202]);
});

test("lo que un agente deja en /tmp se borra tras un día sin uso, y no si algo lo usa", () => {
  const root = mkdtempSync(join(tmpdir(), "kuador-systmp-"));
  mkdirSync(join(root, "ya-estaba"));
  mkdirSync(join(root, "claude-501"));
  const before = tmpSnapshot(root);
  mkdirSync(join(root, "qa-flutter"));
  writeFileSync(join(root, "qa-flutter", "flutter"), "x");
  mkdirSync(join(root, "en-uso"));
  mkdirSync(join(root, "claude-502"));
  assert.deepEqual(recordTmp(before, root).sort(), ["en-uso", "qa-flutter"]);

  // Recién creado: no se borra.
  assert.deepEqual(sweepTmp(root, { running: () => [] }), []);

  for (const p of ["qa-flutter/flutter", "qa-flutter", "en-uso"]) aged(join(root, p), 2);
  const later = Date.now() + 2 * 86_400_000;
  const removed = sweepTmp(root, { now: later, running: () => [`node ${join(root, "en-uso")}/server.js`] });
  assert.deepEqual(removed, ["qa-flutter"]);
  assert.ok(!existsSync(join(root, "qa-flutter")));
  assert.ok(existsSync(join(root, "en-uso")));
  assert.ok(existsSync(join(root, "ya-estaba")));
  assert.ok(existsSync(join(root, "claude-502")));
});

test("se borran los temporales de tarjetas que ya no están en curso", () => {
  jobEnv("MED-4");
  jobEnv("MED-5");
  const removed = sweepJobTmps(new Set(["MED-5"]));
  assert.ok(removed.includes("MED-4"));
  assert.ok(!removed.includes("MED-5"));
});
