import { randomUUID } from "node:crypto";
import { existsSync, rmSync, writeFileSync } from "node:fs";
import { hostname } from "node:os";
import { resolve } from "node:path";

import { api, VERSION } from "./api.js";
import { ensureDirs, HOME, load, PAUSE, readState, requireConfig, save } from "./config.js";
import { start } from "./daemon.js";
import { prompter } from "./prompt.js";
import { install as installService, openBrowser, osName, restart, uninstall as uninstallService } from "./service.js";
import { detect, TOOLS } from "./tools.js";
import { isRepo } from "./workdir.js";

const HELP = `Kuador runner ${VERSION}: deja trabajando a los agentes del tablero en esta máquina.

  install [--server URL]   Conecta esta máquina, elige qué roles atiende e instálalo como servicio
  roles                    Cambia los roles que atiende esta máquina
  link <TABLERO> <carpeta> Vincula el repo de un tablero de software en esta máquina
  status                   Qué está haciendo ahora
  pause | resume           Deja de tomar tarjetas nuevas / vuelve a tomarlas
  start                    Lo ejecuta en primer plano (el servicio usa esto)
  uninstall                Quita el servicio y la conexión de esta máquina
`;

export async function main(argv) {
  const [command = "help", ...rest] = argv;
  const flag = (name) => {
    const i = rest.indexOf(`--${name}`);
    return i >= 0 ? rest[i + 1] : undefined;
  };
  switch (command) {
    case "install":
      return install(flag("server"), rest.includes("--no-service"));
    case "start":
      return start();
    case "roles":
      return roles();
    case "link":
      return link(rest[0], rest[1]);
    case "status":
      return status();
    case "pause":
      ensureDirs();
      writeFileSync(PAUSE, new Date().toISOString());
      return console.log("En pausa: termina lo que está haciendo y no toma tarjetas nuevas. Para seguir: kuador-runner resume");
    case "resume":
      rmSync(PAUSE, { force: true });
      return console.log("Listo: vuelve a tomar tarjetas.");
    case "uninstall":
      return uninstall();
    case "--version":
    case "version":
      return console.log(VERSION);
    default:
      return console.log(HELP);
  }
}

// ---- Instalación ----

async function install(serverFlag, noService = false) {
  ensureDirs();
  const previous = load();
  const p = prompter();
  try {
    console.log(`\nKuador runner ${VERSION}\nDeja a los agentes del tablero trabajando en esta máquina con tu propia cuenta de IA.\n`);

    // 1. La herramienta de IA (hoy Claude Code; Codex y OpenCode en la próxima versión).
    const version = detect("claude");
    if (!version) {
      throw new Error("No encontré Claude Code en esta máquina. Instálalo (https://claude.com/claude-code), inicia sesión con tu cuenta y vuelve a ejecutar la instalación.");
    }
    console.log(`✔ ${TOOLS.claude.label} ${version}: usa tu suscripción de Claude, sin claves de API.`);

    // 2. El tablero.
    const server = (serverFlag || (await p.ask("Dirección del tablero (la que muestra «Agentes locales»)", previous?.server ?? ""))).replace(/\/+$/, "");
    if (!/^https?:\/\//.test(server)) throw new Error("La dirección debe empezar con https://");
    if (!(await api(server).health())) throw new Error(`No pude conectar con ${server}. Revisa la dirección y tu conexión.`);
    const name = await p.ask("Nombre de esta máquina", previous?.name ?? hostname().replace(/\.local$/, ""));

    // 3. Emparejar: la persona aprueba el código con su sesión en el tablero.
    let token = previous?.server === server ? previous.token : null;
    if (token && !(await p.confirm("Esta máquina ya está conectada. ¿Mantener la conexión?", true))) token = null;
    if (!token) token = await pair(server, name);
    const client = api(server, token);
    const setup = await client.setup();
    console.log(`✔ Conectada a nombre de ${setup.person}.\n`);

    // 4. Qué roles atiende.
    console.log("¿Qué roles atiende esta máquina? Solo tomará tarjetas de esos roles.");
    const options = setup.roles.map((r) => ({
      value: r.key,
      label: `${r.name}${r.worksOnCode ? " · escribe código" : ""}${r.local ? "" : " · hoy trabaja en el servidor del tablero"}`,
    }));
    const preset = previous?.roles?.length ? previous.roles : setup.roles.filter((r) => r.local).map((r) => r.key);
    const chosen = await p.choose("Roles", options, preset.filter((k) => options.some((o) => o.value === k)));
    const serverOnly = setup.roles.filter((r) => chosen.includes(r.key) && !r.local);
    if (serverOnly.length) {
      console.log(`  Nota: ${serverOnly.map((r) => r.name).join(", ")} hoy trabaja${serverOnly.length > 1 ? "n" : ""} en el servidor. Para que lo atienda esta máquina, en el tablero cámbialo a «En la PC de una persona».`);
    }
    const capacity = Math.max(1, Math.min(6, Number.parseInt(await p.ask("¿Cuántas tarjetas a la vez? (1 a 6)", String(previous?.capacity ?? 2)), 10) || 2));

    // 5. Repos de los tableros de software (para trabajar en una copia aparte por tarjeta).
    const repos = { ...(previous?.repos ?? {}) };
    const software = setup.projects.filter((x) => x.kind === "SOFTWARE");
    const codeRoles = setup.roles.filter((r) => chosen.includes(r.key) && r.worksOnCode);
    if (software.length && codeRoles.length) {
      console.log("\nRepos: los agentes que escriben código trabajan en una copia aparte del repo por tarjeta.");
      for (const board of software) {
        const answer = await p.ask(`  Carpeta del repo de ${board.name} (${board.key}) en esta máquina (Enter = omitir)`, repos[board.key] ?? "");
        if (!answer) continue;
        const path = resolve(answer.replace(/^~(?=$|\/)/, process.env.HOME ?? "~"));
        if (isRepo(path)) repos[board.key] = path;
        else console.log(`  ✖ ${path} no es un repositorio git: lo omito (puedes vincularlo después con «kuador-runner link ${board.key} <carpeta>»).`);
      }
    }

    // 6. Que no se duerma mientras trabaja.
    let keepAwake = false;
    if (osName() !== "windows") {
      keepAwake = await p.confirm("¿Evitar que la máquina se duerma mientras haya un agente trabajando?", previous?.keepAwake ?? true);
    } else {
      console.log("Nota: en Windows, configura la energía para que no se suspenda mientras quieras que trabaje.");
    }

    const config = {
      server,
      token,
      machineId: previous?.machineId ?? randomUUID(),
      name,
      tool: "claude",
      roles: chosen,
      capacity,
      repos,
      keepAwake,
      timeoutMinutes: previous?.timeoutMinutes ?? 60,
      needsRegister: true,
    };
    save(config);
    await client.heartbeat({ machineId: config.machineId, name, os: osName(), tool: "claude", roles: chosen, capacity, version: VERSION, register: true });
    delete config.needsRegister;
    save(config);

    // 7. Como servicio: arranca solo al prender la máquina.
    const service = noService ? { kind: "sin servicio: ejecuta «kuador-runner start»" } : installService();
    console.log(`\n✔ Listo. «${name}» atiende: ${setup.roles.filter((r) => chosen.includes(r.key)).map((r) => r.name).join(", ")} (hasta ${capacity} a la vez).`);
    console.log(noService ? "  Para que trabaje: npx @kuador/runner start. Lo ves en el tablero, en «Agentes locales»." : `  Arranca solo al iniciar sesión (${service.kind}). Lo ves en el tablero, en «Agentes locales».`);
    console.log("  Qué está haciendo: npx @kuador/runner status   ·   Registros: " + HOME + "/logs\n");
  } finally {
    p.close();
  }
}

/** Empareja como una TV: muestra el código, abre el tablero y espera a que la persona lo apruebe. */
async function pair(server, name) {
  const client = api(server);
  const started = await client.pair(name);
  console.log(`\nAprueba esta máquina en el tablero con el código:\n\n      ${started.userCode}\n\n  ${started.verifyUrl}\n`);
  if (openBrowser(started.verifyUrl)) console.log("  (Abrí el enlace en tu navegador.)");
  const deadline = Date.now() + started.expiresIn * 1000;
  process.stdout.write("  Esperando la aprobación");
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, started.interval * 1000));
    const r = await client.pairToken(started.deviceCode);
    if (r.status === 200) {
      process.stdout.write(" ✔\n");
      return r.body.token;
    }
    if (r.status === 410) break;
    process.stdout.write(".");
  }
  process.stdout.write("\n");
  throw new Error("El código venció sin aprobarse. Vuelve a ejecutar la instalación.");
}

// ---- Ajustes ----

async function roles() {
  const config = requireConfig();
  const client = api(config.server, config.token);
  const setup = await client.setup();
  const p = prompter();
  try {
    const options = setup.roles.map((r) => ({ value: r.key, label: `${r.name}${r.worksOnCode ? " · escribe código" : ""}` }));
    config.roles = await p.choose("Roles que atiende esta máquina", options, config.roles ?? []);
  } finally {
    p.close();
  }
  await client.heartbeat({ machineId: config.machineId, name: config.name, os: osName(), tool: config.tool, roles: config.roles, capacity: config.capacity, version: VERSION, register: true });
  save(config);
  restart();
  console.log(`Listo: ahora atiende ${config.roles.join(", ")}.`);
}

function link(board, folder) {
  if (!board || !folder) throw new Error("Uso: kuador-runner link <TABLERO> <carpeta del repo>");
  const config = requireConfig();
  const path = resolve(folder);
  if (!isRepo(path)) throw new Error(`${path} no es un repositorio git.`);
  config.repos = { ...(config.repos ?? {}), [board.toUpperCase()]: path };
  save(config);
  restart();
  console.log(`Listo: las tarjetas de ${board.toUpperCase()} trabajan en copias de ${path}.`);
}

function status() {
  const config = requireConfig();
  const s = readState();
  console.log(`${config.name} · ${config.server}`);
  console.log(`Roles: ${(config.roles ?? []).join(", ")} · hasta ${config.capacity} a la vez`);
  if (!s) return console.log("El runner todavía no arrancó (o no está corriendo como servicio).");
  const age = (Date.now() - new Date(s.updatedAt).getTime()) / 1000;
  if (age > 120) console.log(`⚠ Sin señal desde hace ${Math.round(age / 60)} min: ¿está corriendo el servicio?`);
  if (s.paused) console.log("⏸ Pausada desde el tablero.");
  if (s.localPaused || existsSync(PAUSE)) console.log("⏸ En pausa en esta máquina (kuador-runner resume para seguir).");
  if (s.pausedUntil) console.log(`⏳ Límite de la suscripción: retoma a las ${new Date(s.pausedUntil).toLocaleTimeString()}.`);
  if (!s.jobs.length) return console.log("Sin tarjetas en curso: esperando trabajo.");
  for (const j of s.jobs) console.log(`▶ ${j.issue} · ${j.role} · ${j.title} (desde ${new Date(j.since).toLocaleTimeString()})`);
}

function uninstall() {
  uninstallService();
  const config = load();
  rmSync(HOME + "/runner.json", { force: true });
  console.log("Listo: quité el servicio y la conexión de esta máquina." + (config ? " En el tablero, «Agentes locales», puedes quitarla de la lista." : ""));
}
