import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

process.env.KUADOR_HOME = mkdtempSync(join(tmpdir(), "kuador-sessions-"));
const { forget, remember, resumable } = await import("../src/sessions.js");

test("retoma solo la misma tarjeta, con el mismo rol y en la misma carpeta", () => {
  const dir = join(process.env.KUADOR_HOME, "work", "MED-12");
  mkdirSync(dir, { recursive: true });
  const claim = { issueKey: "MED-12", role: "BACKEND" };
  assert.equal(resumable(claim, dir), null);
  remember(claim, dir, "sess-1");
  assert.equal(resumable(claim, dir), "sess-1");
  assert.equal(resumable({ issueKey: "MED-12", role: "QA" }, dir), null, "otro rol empieza de cero");
  assert.equal(resumable(claim, join(dir, "otra")), null, "otra carpeta empieza de cero");
  forget(claim);
  assert.equal(resumable(claim, dir), null);
});
