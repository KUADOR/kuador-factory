import { spawn, spawnSync } from "node:child_process";
import { existsSync, writeFileSync } from "node:fs";
import { delimiter, dirname, join } from "node:path";

import { SECRETS } from "./config.js";

/** Herramientas de IA que el runner sabe lanzar. Codex y OpenCode llegan en la próxima versión. */
export const TOOLS = {
  claude: { label: "Claude Code", command: "claude", ready: true },
  codex: { label: "Codex", command: "codex", ready: false },
  opencode: { label: "OpenCode", command: "opencode", ready: false },
};

/**
 * Cómo lanzar la herramienta sin pasar por un shell: el texto de las tarjetas va en los argumentos y, con `cmd.exe`,
 * unas comillas o un `&` en una tarjeta ejecutarían comandos en la PC. En Mac y Linux se lanza tal cual. En Windows:
 * el `.exe` del instalador nativo, o, si se instaló con npm (`claude.cmd`), `node` con el `cli.js` del paquete.
 * Devuelve {command, prefix} (los argumentos que van antes de los propios) o null si no se encontró.
 */
export function resolve(name, { platform = process.platform, path = process.env.PATH ?? "", nodePath = process.execPath, exists = existsSync } = {}) {
  if (platform !== "win32") return { command: name, prefix: [] };
  const dirs = path.split(platform === "win32" ? ";" : delimiter).filter(Boolean);
  for (const dir of dirs) {
    const exe = join(dir, `${name}.exe`);
    if (exists(exe)) return { command: exe, prefix: [] };
  }
  for (const dir of dirs) {
    if (!exists(join(dir, `${name}.cmd`))) continue;
    const cli = NPM_CLI[name] && join(dir, "node_modules", ...NPM_CLI[name]);
    if (cli && exists(cli)) return { command: nodePath, prefix: [cli] };
  }
  return null;
}

/** Dónde deja npm el punto de entrada de cada herramienta, junto a su atajo `.cmd`. */
const NPM_CLI = { claude: ["@anthropic-ai", "claude-code", "cli.js"] };

/** Lanza la herramienta sin shell; si en Windows no se puede, el error explica cómo instalarla. */
function run(name, args, options) {
  const how = resolve(name);
  if (!how) {
    const error = new Error(`No encontré ${name} en el PATH. En Windows instálalo con su instalador (deja ${name}.exe) o con npm (npm i -g).`);
    return { how: null, error };
  }
  return { how, child: spawn(how.command, [...how.prefix, ...args], { ...options, shell: false }) };
}

/** Versión instalada de la herramienta, o null si no está. */
export function detect(tool) {
  const how = resolve(TOOLS[tool].command);
  if (!how) return null;
  const r = spawnSync(how.command, [...how.prefix, "--version"], { encoding: "utf8", shell: false, timeout: 15_000, windowsHide: true });
  return r.status === 0 ? (r.stdout || r.stderr).trim().split("\n")[0] : null;
}

/** Modelos que entiende Claude Code (los del tablero pueden ser de otro proveedor: entonces usa el de tu cuenta). */
const claudeModel = (model) => (model && /^(claude-|opus|sonnet|haiku|fable)/i.test(model) ? model : null);
const EFFORTS = ["low", "medium", "high", "xhigh", "max"];

/**
 * Lanza el cliente de IA con la tarjeta. Le da la credencial de esta ejecución (solo esta tarjeta, solo este rol, solo
 * mientras dure el préstamo), nunca la conexión larga de la máquina; va en un archivo fuera de la carpeta de trabajo
 * (nunca dentro del repo) y solo legible por ti. Devuelve el proceso y una promesa con el resultado.
 */
export function launch(config, claim, cwd, logStream, resumeId = null) {
  const mcpFile = join(SECRETS, `${claim.issueKey}.json`);
  writeFileSync(
    mcpFile,
    JSON.stringify({ mcpServers: { [claim.mcpServer]: { type: "http", url: claim.mcpUrl, headers: { Authorization: `Bearer ${claim.runToken ?? config.token}` } } } }),
    { mode: 0o600 },
  );
  // Al retomar, la conversación ya tiene el contexto: el pedido es solo lo nuevo (lo arma el tablero).
  const args = [
    ...(resumeId ? ["--resume", resumeId] : []),
    "-p",
    resumeId && claim.resumePrompt ? claim.resumePrompt : claim.prompt,
    "--mcp-config",
    mcpFile,
    "--strict-mcp-config",
    "--append-system-prompt",
    claim.instructions,
    "--allowedTools",
    claim.allowedTools.join(","),
    "--output-format",
    "json",
  ];
  const model = claudeModel(claim.model);
  if (model) args.push("--model", model);
  if (claim.effort && EFFORTS.includes(claim.effort)) args.push("--effort", claim.effort);

  const started = run(TOOLS.claude.command, args, { cwd, env: { ...process.env, KUADOR_ISSUE: claim.issueKey }, windowsHide: true });
  if (!started.child) return { child: null, done: Promise.resolve({ code: -1, out: "", err: String(started.error) }) };
  const { child } = started;
  let out = "";
  let err = "";
  child.stdout.on("data", (d) => {
    out += d;
    logStream?.write(d);
  });
  child.stderr.on("data", (d) => {
    err += d;
    logStream?.write(d);
  });
  const done = new Promise((resolve) => {
    child.on("error", (e) => resolve({ code: -1, out, err: err + String(e) }));
    child.on("close", (code) => resolve({ code, out, err }));
  });
  return { child, done };
}

/**
 * Un pedido de texto del tablero (p. ej. redactar los documentos base «en mi computador»): sin servidores MCP ni
 * herramientas que cambien algo, en una carpeta vacía. Con {@code readOnlyRepo} (reconocer un proyecto existente),
 * en la carpeta del repo y solo con las herramientas de lectura. Devuelve el texto de la respuesta.
 */
export function ask(system, user, cwd, { readOnlyRepo = false } = {}) {
  const args = ["-p", user, "--append-system-prompt", system, "--output-format", "json", "--strict-mcp-config",
    "--disallowedTools", "Bash,Edit,Write,MultiEdit,NotebookEdit,WebFetch,WebSearch"];
  if (readOnlyRepo) args.push("--allowedTools", "Read,Grep,Glob,LS");
  const started = run(TOOLS.claude.command, args, { cwd, windowsHide: true });
  if (!started.child) return Promise.resolve({ text: null, error: String(started.error) });
  const { child } = started;
  let out = "";
  let err = "";
  child.stdout.on("data", (d) => (out += d));
  child.stderr.on("data", (d) => (err += d));
  return new Promise((resolve) => {
    child.on("error", (e) => resolve({ text: null, error: String(e) }));
    child.on("close", (code) => {
      let result = null;
      try {
        result = JSON.parse(out.trim().split("\n").filter(Boolean).pop() ?? "");
      } catch {
        // Sin JSON: se informa el error.
      }
      if (code === 0 && result && !result.is_error) resolve({ text: String(result.result ?? ""), error: null });
      else resolve({ text: null, error: `Claude Code: ${(result?.result || err || out || `salió con código ${code}`).toString().trim().slice(-400)}` });
    });
  });
}

/** Interpreta lo que devolvió Claude Code: terminó bien, falló o llegó al límite de la suscripción. */
export function outcome({ code, out, err }) {
  const text = `${out}\n${err}`;
  let result = null;
  try {
    result = JSON.parse(out.trim().split("\n").filter(Boolean).pop() ?? "");
  } catch {
    // No es JSON: se mira el texto.
  }
  if (/usage limit|rate.?limit|limit reached|quota|5-hour limit|weekly limit/i.test(text) && (code !== 0 || result?.is_error)) {
    return { outcome: "RATE_LIMITED", message: "La suscripción de IA llegó a su límite de uso.", resumeAt: resetTime(text) };
  }
  const sessionId = typeof result?.session_id === "string" ? result.session_id : null;
  // La sesión para retomar ya no existe (se borró o venció en Claude Code): se reintenta de cero, no es un fallo.
  if (code !== 0 && /no conversation found|session.*not found|could not find session/i.test(text)) return { outcome: "RESUME_MISSING", sessionId };
  if (code === 0 && !result?.is_error) return { outcome: "DONE", message: String(result?.result ?? "").slice(0, 300), usage: result?.usage, sessionId };
  const why = (result?.result || err || out || `salió con código ${code}`).toString().trim().split("\n").slice(-3).join(" ");
  return { outcome: "FAILED", message: `Claude Code: ${why}`.slice(0, 480), sessionId };
}

/** Si el mensaje dice cuándo se renueva el límite; si no, en 30 minutos se vuelve a intentar. */
function resetTime(text) {
  const epoch = text.match(/\|(\d{10})\b/);
  if (epoch) return new Date(Number(epoch[1]) * 1000);
  return new Date(Date.now() + 30 * 60_000);
}
