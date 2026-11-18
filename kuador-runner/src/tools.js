import { spawn, spawnSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { join } from "node:path";

import { SECRETS } from "./config.js";

const WINDOWS = process.platform === "win32";

/** Herramientas de IA que el runner sabe lanzar. Codex y OpenCode llegan en la próxima versión. */
export const TOOLS = {
  claude: { label: "Claude Code", command: "claude", ready: true },
  codex: { label: "Codex", command: "codex", ready: false },
  opencode: { label: "OpenCode", command: "opencode", ready: false },
};

/** Versión instalada de la herramienta, o null si no está. */
export function detect(tool) {
  const r = spawnSync(TOOLS[tool].command, ["--version"], { encoding: "utf8", shell: WINDOWS, timeout: 15_000 });
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

  const child = spawn(TOOLS.claude.command, args, { cwd, shell: WINDOWS, env: { ...process.env, KUADOR_ISSUE: claim.issueKey }, windowsHide: true });
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
