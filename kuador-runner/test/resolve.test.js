import assert from "node:assert/strict";
import { join } from "node:path";
import { test } from "node:test";

import { resolve } from "../src/tools.js";

// Las rutas se arman con join del sistema donde corren las pruebas; solo importa qué archivo existe.
const files = (...paths) => {
  const set = new Set(paths);
  return (p) => set.has(p);
};

test("Mac y Linux: se lanza tal cual, sin shell", () => {
  assert.deepEqual(resolve("claude", { platform: "darwin", path: "/usr/bin" }), { command: "claude", prefix: [] });
});

test("Windows con el instalador nativo: el .exe directo", () => {
  const exe = join("C:\\Users\\ana\\.local\\bin", "claude.exe");
  const r = resolve("claude", { platform: "win32", path: "C:\\Windows;C:\\Users\\ana\\.local\\bin", exists: files(exe) });
  assert.deepEqual(r, { command: exe, prefix: [] });
});

test("Windows con npm: node con el cli.js del paquete, nunca el atajo .cmd por cmd.exe", () => {
  const dir = "C:\\Users\\ana\\AppData\\Roaming\\npm";
  const cli = join(dir, "node_modules", "@anthropic-ai", "claude-code", "cli.js");
  const r = resolve("claude", { platform: "win32", path: dir, nodePath: "C:\\node\\node.exe", exists: files(join(dir, "claude.cmd"), cli) });
  assert.deepEqual(r, { command: "C:\\node\\node.exe", prefix: [cli] });
});

test("Windows sin Claude Code: null (el runner lo explica en vez de usar un shell)", () => {
  assert.equal(resolve("claude", { platform: "win32", path: "C:\\Windows", exists: () => false }), null);
});
