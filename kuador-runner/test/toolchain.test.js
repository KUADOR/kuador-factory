import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { beforeEach, test } from "node:test";

import { _test, environment, freePorts, jdks, requirements, takePorts } from "../src/toolchain.js";

/** Un repo con piezas en carpetas, como los de las fábricas (api/, web/, movil/). */
function repo(files) {
  const root = mkdtempSync(join(tmpdir(), "kuador-tc-"));
  for (const [path, text] of Object.entries(files)) {
    mkdirSync(join(root, path, ".."), { recursive: true });
    writeFileSync(join(root, path), text);
  }
  return root;
}

const POM = "<project><properties><java.version>25</java.version></properties></project>";
const found = {
  jdks: [
    { home: "/jdk/25", major: 25, version: "25.0.2" },
    { home: "/jdk/21", major: 21, version: "21.0.5" },
  ],
  flutters: [{ bin: "/tools/flutter-3.50.1/bin", version: "3.50.1" }],
  nodes: [{ bin: "/node/22/bin", major: 22 }],
};

beforeEach(() => _test.reset());

test("lee lo que pide cada pieza del repo", () => {
  const dir = repo({
    "api/pom.xml": POM,
    "worker/build.gradle.kts": "kotlin { jvmToolchain(21) }",
    "movil/pubspec.yaml": "name: app\ndependencies:\n  flutter:\n    sdk: flutter\n",
    "web/package.json": JSON.stringify({ engines: { node: ">=22" } }),
    "node_modules/x/pom.xml": "<java.version>8</java.version>",
  });
  assert.deepEqual(requirements(dir), { java: [25, 21], flutter: "*", node: 22 });
});

test("una versión exacta de Flutter o Java en los archivos de versiones", () => {
  const dir = repo({ ".fvmrc": JSON.stringify({ flutter: "3.35.1" }), ".tool-versions": "java temurin-17.0.9\nnodejs 20.11.0\n" });
  assert.deepEqual(requirements(dir), { java: [17], flutter: "3.35.1", node: 20 });
});

test("deja Java, Flutter y Node listos y se lo dice al agente", () => {
  const dir = repo({ "api/pom.xml": POM, "movil/pubspec.yaml": "dependencies:\n  flutter:\n", ".nvmrc": "22" });
  const r = environment(dir, "MED-1", { found, docker: true, path: "/usr/bin" });
  assert.equal(r.env.JAVA_HOME, "/jdk/25");
  assert.equal(r.env.PATH.split(":")[0], "/jdk/25/bin");
  assert.ok(r.env.PATH.includes("/tools/flutter-3.50.1/bin") && r.env.PATH.endsWith("/usr/bin"));
  assert.match(r.notes, /Java 25 \(25\.0\.2\): JAVA_HOME ya apunta a \/jdk\/25/);
  assert.match(r.notes, /Flutter 3\.50\.1/);
  assert.match(r.notes, /Docker responde/);
  assert.doesNotMatch(r.notes, /Falta/);
});

test("lo que no está se dice, con dónde instalarlo", () => {
  const dir = repo({ ".fvmrc": JSON.stringify({ flutter: "3.10.0" }), "pom.xml": "<java.version>11</java.version>" });
  const r = environment(dir, "MED-2", { found, docker: false, path: "/usr/bin" });
  assert.equal(r.env.JAVA_HOME, undefined);
  assert.equal(r.env.PATH, undefined, "sin cambios en el PATH");
  assert.match(r.notes, /Falta en esta máquina: Java 11, Flutter 3\.10\.0/);
  assert.match(r.notes, /Docker no responde/);
});

test("cada tarjeta en curso tiene sus propios puertos y se liberan al terminar", () => {
  const a = takePorts("A-1");
  const b = takePorts("B-1");
  assert.notEqual(a.from, b.from);
  assert.deepEqual(takePorts("A-1"), a, "el mismo mientras siga");
  freePorts("A-1");
  assert.deepEqual(takePorts("C-1"), a, "se reutiliza el que quedó libre");
  const r = environment(repo({}), "B-1", { found, docker: true });
  assert.equal(r.env.KUADOR_PORTS, `${b.from}-${b.to}`);
});

test("encuentra los JDK por su archivo release", () => {
  const root = repo({
    "zulu-25/release": 'JAVA_VERSION="25.0.2"\n',
    "zulu-25/bin/java": "",
    "temurin-21.jdk/Contents/Home/release": 'JAVA_VERSION="21.0.5"\n',
    "temurin-21.jdk/Contents/Home/bin/java": "",
    "roto/release": 'JAVA_VERSION="17"\n',
  });
  assert.deepEqual(jdks({ roots: [root] }).map((j) => [j.major, j.version]), [[25, "25.0.2"], [21, "21.0.5"]]);
});
