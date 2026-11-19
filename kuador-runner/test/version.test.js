import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { VERSION } from "../src/api.js";

test("la versión del código coincide con la de package.json (la que publica npm)", () => {
  const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
  assert.equal(VERSION, pkg.version);
});
