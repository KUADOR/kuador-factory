import assert from "node:assert/strict";
import { test } from "node:test";

import { outcome } from "../src/tools.js";

test("terminó bien: resultado de Claude Code en JSON", () => {
  const r = outcome({ code: 0, out: JSON.stringify({ type: "result", is_error: false, result: "MED-3 entregado a QA" }), err: "" });
  assert.equal(r.outcome, "DONE");
  assert.match(r.message, /MED-3/);
});

test("límite de la suscripción: se pausa (no cuenta como corte)", () => {
  const r = outcome({ code: 1, out: JSON.stringify({ is_error: true, result: "Claude AI usage limit reached|1900000000" }), err: "" });
  assert.equal(r.outcome, "RATE_LIMITED");
  assert.equal(r.resumeAt.getTime(), 1900000000 * 1000);
});

test("falló: se informa el motivo", () => {
  const r = outcome({ code: 1, out: "", err: "Error: something broke\n" });
  assert.equal(r.outcome, "FAILED");
  assert.match(r.message, /something broke/);
});

test("el texto «rate limit» en una respuesta correcta no pausa", () => {
  const r = outcome({ code: 0, out: JSON.stringify({ is_error: false, result: "Documenté el rate limit de la API" }), err: "" });
  assert.equal(r.outcome, "DONE");
});
