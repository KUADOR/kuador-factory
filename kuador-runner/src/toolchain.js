import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { delimiter, join } from "node:path";

import { TOOLS } from "./hygiene.js";

/**
 * El entorno de cada tarjeta, listo antes de lanzar al agente: qué Java, Flutter y Node pide el repo (lo dicen sus
 * archivos, no el tablero), dónde están en esta máquina, si Docker responde y qué puertos puede usar. Así el agente no
 * gasta turnos buscándolos (en 169 de 292 sesiones lo hacía, unas 4 veces por sesión).
 */

const read = (path) => {
  try {
    return readFileSync(path, "utf8");
  } catch {
    return null;
  }
};

const dirs = (path) => {
  try {
    return readdirSync(path, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => join(path, e.name));
  } catch {
    return [];
  }
};

/** Carpetas donde mirar: la raíz del repo y sus carpetas de primer nivel (api/, web/, movil/…), sin las ocultas ni dependencias. */
function folders(root) {
  const skip = /^(\.|node_modules$|target$|build$|dist$)/;
  return [root, ...dirs(root).filter((d) => !skip.test(d.slice(root.length + 1)))];
}

/** Si hay un ejecutable en una carpeta bin (en Windows, con su extensión). */
const has = (bin, name) => ["", ".exe", ".bat", ".cmd"].some((ext) => existsSync(join(bin, name + ext)));

const major = (v) => {
  const m = String(v ?? "").match(/(?:^|[^\d])(1\.)?(\d+)/);
  return m ? Number(m[2]) : null;
};

/** Lo que pide el repo: {java: [mayores], flutter: versión exacta o "*", node: mayor}. */
export function requirements(root) {
  const java = new Set();
  let flutter = null;
  let node = null;
  for (const dir of folders(root)) {
    const tv = read(join(dir, ".tool-versions"));
    if (tv) {
      for (const line of tv.split("\n")) {
        const [tool, v = ""] = line.trim().split(/\s+/);
        const jv = major(v.replace(/^[a-z]+-/i, ""));
        if (tool === "java" && jv) java.add(jv);
        if (tool === "flutter" && v) flutter = v.replace(/-stable$/, "");
        if ((tool === "nodejs" || tool === "node") && major(v)) node ??= major(v);
      }
    }
    const sdkman = read(join(dir, ".sdkmanrc"))?.match(/^java=([^\s]+)/m);
    if (sdkman && major(sdkman[1])) java.add(major(sdkman[1]));
    const jv = read(join(dir, ".java-version"));
    if (jv && major(jv)) java.add(major(jv));
    const pom = read(join(dir, "pom.xml"));
    const pomV = pom?.match(/<(?:java\.version|maven\.compiler\.release|maven\.compiler\.source)>\s*([\d.]+)\s*</);
    if (pomV && major(pomV[1])) java.add(major(pomV[1]));
    const gradle = read(join(dir, "build.gradle.kts")) ?? read(join(dir, "build.gradle"));
    const gradleV = gradle?.match(/(?:JavaLanguageVersion\.of|jvmToolchain)\(\s*(\d+)\s*\)|sourceCompatibility\s*=\s*['"]?(?:JavaVersion\.VERSION_)?(\d+)/);
    if (gradleV) java.add(Number(gradleV[1] ?? gradleV[2]));

    const fvm = read(join(dir, ".fvmrc")) ?? read(join(dir, ".fvm", "fvm_config.json"));
    if (fvm) {
      try {
        const c = JSON.parse(fvm);
        flutter = c.flutter ?? c.flutterSdkVersion ?? flutter;
      } catch {
        // Un .fvmrc roto no dice versión.
      }
    }
    if (!flutter && /^\s*flutter\s*:/m.test(read(join(dir, "pubspec.yaml")) ?? "")) flutter = "*";

    const nvm = read(join(dir, ".nvmrc")) ?? read(join(dir, ".node-version"));
    if (nvm && major(nvm)) node ??= major(nvm);
    const pkg = read(join(dir, "package.json"));
    if (pkg && node === null) {
      try {
        const engines = JSON.parse(pkg).engines?.node;
        if (engines && major(engines)) node = major(engines);
      } catch {
        // package.json roto: sin versión pedida.
      }
    }
  }
  return { java: [...java].sort((a, b) => b - a), flutter, node };
}

// ── Dónde está cada herramienta en esta máquina ──

const HOME = homedir();

/** Los JDK instalados: [{home, major, version}], del más nuevo al más viejo. */
export function jdks({ roots } = {}) {
  const homes = [];
  const candidates = roots ?? [
    join(HOME, ".sdkman", "candidates", "java"),
    "/Library/Java/JavaVirtualMachines",
    join(HOME, "Library", "Java", "JavaVirtualMachines"),
    "/usr/lib/jvm",
    TOOLS,
  ];
  for (const root of candidates) {
    for (const d of dirs(root)) {
      if (d.endsWith("current")) continue;
      for (const home of [d, join(d, "Contents", "Home")]) {
        const release = read(join(home, "release"));
        const v = release?.match(/^JAVA_VERSION="([^"]+)"/m);
        if (v && has(join(home, "bin"), "java")) homes.push({ home, major: major(v[1]), version: v[1] });
      }
    }
  }
  const seen = new Set();
  return homes.filter((j) => !seen.has(j.home) && seen.add(j.home)).sort((a, b) => b.version.localeCompare(a.version, undefined, { numeric: true }));
}

/** Los SDK de Flutter: [{bin, version}], del más nuevo al más viejo. */
export function flutters({ roots, path = process.env.PATH ?? "" } = {}) {
  const found = [];
  const add = (sdk) => {
    const bin = join(sdk, "bin");
    if (!has(bin, "flutter")) return;
    const json = read(join(sdk, "bin", "cache", "flutter.version.json"));
    let version = null;
    try {
      version = json ? JSON.parse(json).frameworkVersion ?? JSON.parse(json).flutterVersion : null;
    } catch {
      // Sin la versión en caché: se mira el archivo «version».
    }
    version ??= read(join(sdk, "version"))?.trim() ?? sdk.match(/(\d+\.\d+\.\d+)/)?.[1] ?? null;
    found.push({ bin, version });
  };
  for (const d of dirs(TOOLS).filter((d) => /flutter/i.test(d))) add(d);
  for (const root of roots ?? [join(HOME, "fvm", "versions"), join(HOME, "flutter"), join(HOME, "development")]) {
    add(root);
    for (const d of dirs(root)) add(d);
  }
  for (const p of path.split(delimiter)) if (p.endsWith(join("flutter", "bin"))) add(p.slice(0, -4));
  const seen = new Set();
  return found
    .filter((f) => !seen.has(f.bin) && seen.add(f.bin))
    .sort((a, b) => String(b.version ?? "").localeCompare(String(a.version ?? ""), undefined, { numeric: true }));
}

/** Los Node: [{bin, major}] (el que corre el runner primero, luego nvm y la carpeta compartida). */
export function nodes() {
  const found = [{ bin: join(process.execPath, ".."), major: major(process.versions.node) }];
  for (const root of [join(HOME, ".nvm", "versions", "node"), TOOLS]) {
    for (const d of dirs(root)) {
      const name = d.slice(root.length + 1);
      if (root === TOOLS && !/^node/i.test(name)) continue;
      if (has(join(d, "bin"), "node")) found.push({ bin: join(d, "bin"), major: major(name) });
    }
  }
  return found;
}

let docker = { at: 0, ok: null };
/** Si Docker responde (para Testcontainers y docker compose). Se pregunta cada 10 minutos como mucho. */
export function dockerReady({ now = Date.now(), check } = {}) {
  if (docker.ok !== null && now - docker.at < 10 * 60_000) return docker.ok;
  const ok = check ? check() : spawnSync("docker", ["info", "--format", "{{.ServerVersion}}"], { timeout: 8_000, encoding: "utf8" }).status === 0;
  docker = { at: now, ok };
  return ok;
}

// ── Puertos: un bloque propio por tarjeta, para que dos agentes no se pisen ──

const PORT_START = 41000;
const PORT_BLOCK = 20;
const blocks = new Map();

/** El bloque de puertos de una tarjeta ({from, to}); el mismo mientras siga en curso. */
export function takePorts(issueKey) {
  if (!blocks.has(issueKey)) {
    const used = new Set(blocks.values());
    let i = 0;
    while (used.has(i)) i++;
    blocks.set(issueKey, i);
  }
  const from = PORT_START + blocks.get(issueKey) * PORT_BLOCK;
  return { from, to: from + PORT_BLOCK - 1 };
}

export function freePorts(issueKey) {
  blocks.delete(issueKey);
}

// ── Todo junto ──

/**
 * Variables y aviso para el agente de una tarjeta. {@code env}: JAVA_HOME y el PATH con las versiones pedidas
 * primero, y los puertos. {@code notes}: lo que se le cuenta al agente (qué hay, dónde, y qué falta).
 */
export function environment(dir, issueKey, { found = { jdks: jdks(), flutters: flutters(), nodes: nodes() }, docker = dockerReady(), path = process.env.PATH ?? "" } = {}) {
  const need = requirements(dir);
  const env = {};
  const bins = [];
  const notes = [];
  const missing = [];

  for (const [i, want] of need.java.entries()) {
    const jdk = found.jdks.find((j) => j.major === want);
    if (!jdk) {
      missing.push(`Java ${want}`);
      continue;
    }
    if (i === 0) {
      env.JAVA_HOME = jdk.home;
      bins.push(join(jdk.home, "bin"));
      notes.push(`- Java ${want} (${jdk.version}): JAVA_HOME ya apunta a ${jdk.home} y su java está primero en el PATH. No la busques.`);
    } else {
      notes.push(`- Java ${want} (${jdk.version}) también está, en ${jdk.home}: úsala con JAVA_HOME=${jdk.home} donde se pida esa versión.`);
    }
  }
  if (need.flutter) {
    const exact = need.flutter !== "*" ? found.flutters.find((f) => f.version === need.flutter) : null;
    const sdk = exact ?? (need.flutter === "*" ? found.flutters[0] : null);
    if (sdk) {
      bins.push(sdk.bin);
      notes.push(`- Flutter${sdk.version ? ` ${sdk.version}` : ""}: ya está primero en el PATH (${sdk.bin}). No lo busques.`);
    } else {
      missing.push(`Flutter ${need.flutter}`);
    }
  }
  if (need.node) {
    const n = found.nodes.find((x) => x.major === need.node);
    if (n) {
      bins.push(n.bin);
      notes.push(`- Node ${need.node}: ya está primero en el PATH.`);
    } else {
      missing.push(`Node ${need.node}`);
    }
  }
  if (bins.length) env.PATH = [...bins, path].join(delimiter);

  const ports = takePorts(issueKey);
  env.KUADOR_PORTS = `${ports.from}-${ports.to}`;
  notes.push(
    `- Puertos para tus servidores y pruebas: del ${ports.from} al ${ports.to} ($KUADOR_PORTS). Otros agentes trabajan en esta máquina a la vez: no uses los puertos por defecto (8080, 3000, 4200, 5432…); pásale uno de los tuyos a cada servidor que levantes.`,
  );
  notes.push(
    docker
      ? "- Docker responde: las pruebas con Testcontainers o docker compose pueden correr."
      : "- Docker no responde en esta máquina: las pruebas que lo necesitan (Testcontainers, docker compose) no van a correr. No intentes arrancarlo; corre las demás y dilo en tu entrega.",
  );
  if (missing.length) {
    notes.push(`- Falta en esta máquina: ${missing.join(", ")}. Instálala en ${TOOLS} como <nombre>-<versión> y úsala desde ahí.`);
  }
  return { env, notes: ["Entorno listo para esta tarjeta (lo preparó el runner):", ...notes].join("\n"), need };
}

export const _test = { reset: () => ((docker = { at: 0, ok: null }), blocks.clear()) };
