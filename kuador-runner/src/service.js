import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { APP, LOGS } from "./config.js";

const LABEL = "ai.kuador.runner";
const here = dirname(dirname(fileURLToPath(import.meta.url)));
// Sin shell: launchctl, systemctl y schtasks son ejecutables; las rutas van como argumentos, nunca interpretadas.
const run = (cmd, args) => spawnSync(cmd, args, { encoding: "utf8", shell: false, windowsHide: true });

/** Sistema operativo con nombre legible. */
export function osName() {
  return { darwin: "macos", linux: "linux", win32: "windows" }[process.platform] ?? process.platform;
}

/**
 * Copia el runner a ~/.kuador/app (npx lo deja en una carpeta temporal) y lo deja como servicio que arranca solo al
 * iniciar sesión: launchd en Mac, systemd de usuario en Linux y el Programador de tareas en Windows.
 */
export function install() {
  rmSync(APP, { recursive: true, force: true });
  mkdirSync(APP, { recursive: true });
  for (const part of ["bin", "src", "package.json"]) cpSync(join(here, part), join(APP, part), { recursive: true });
  const node = process.execPath;
  const script = join(APP, "bin", "kuador-runner.js");
  const path = process.env.PATH ?? "";

  if (process.platform === "darwin") {
    const plist = join(homedir(), "Library", "LaunchAgents", `${LABEL}.plist`);
    mkdirSync(dirname(plist), { recursive: true });
    writeFileSync(
      plist,
      `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key><array><string>${node}</string><string>${script}</string><string>start</string></array>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>${xml(path)}</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>${join(LOGS, "service.log")}</string>
  <key>StandardErrorPath</key><string>${join(LOGS, "service.log")}</string>
</dict></plist>
`,
    );
    const domain = `gui/${process.getuid()}`;
    run("launchctl", ["bootout", domain, plist]);
    const r = run("launchctl", ["bootstrap", domain, plist]);
    if (r.status !== 0) throw new Error(`launchd no aceptó el servicio: ${r.stderr.trim()}`);
    return { kind: "launchd", file: plist };
  }

  if (process.platform === "linux") {
    const unit = join(homedir(), ".config", "systemd", "user", "kuador-runner.service");
    mkdirSync(dirname(unit), { recursive: true });
    writeFileSync(
      unit,
      `[Unit]
Description=Kuador runner: agentes del tablero en esta máquina
After=network-online.target

[Service]
ExecStart="${node}" "${script}" start
Environment="PATH=${path}"
Restart=always
RestartSec=30

[Install]
WantedBy=default.target
`,
    );
    run("systemctl", ["--user", "daemon-reload"]);
    const r = run("systemctl", ["--user", "enable", "--now", "kuador-runner.service"]);
    if (r.status !== 0) throw new Error(`systemd no aceptó el servicio: ${r.stderr.trim()}`);
    // Para que siga trabajando sin una sesión abierta (p. ej. por SSH).
    run("loginctl", ["enable-linger", process.env.USER ?? ""]);
    return { kind: "systemd", file: unit };
  }

  if (process.platform === "win32") {
    const r = run("schtasks", ["/Create", "/TN", "KuadorRunner", "/SC", "ONLOGON", "/RL", "LIMITED", "/F", "/TR", `"\\"${node}\\" \\"${script}\\" start"`]);
    if (r.status !== 0) throw new Error(`El Programador de tareas no aceptó el servicio: ${(r.stderr || r.stdout).trim()}`);
    run("schtasks", ["/Run", "/TN", "KuadorRunner"]);
    return { kind: "schtasks", file: "KuadorRunner" };
  }
  throw new Error(`Sistema no soportado para servicio: ${process.platform}. Usa «kuador-runner start».`);
}

export function uninstall() {
  if (process.platform === "darwin") {
    const plist = join(homedir(), "Library", "LaunchAgents", `${LABEL}.plist`);
    run("launchctl", ["bootout", `gui/${process.getuid()}`, plist]);
    if (existsSync(plist)) rmSync(plist);
  } else if (process.platform === "linux") {
    run("systemctl", ["--user", "disable", "--now", "kuador-runner.service"]);
    rmSync(join(homedir(), ".config", "systemd", "user", "kuador-runner.service"), { force: true });
  } else if (process.platform === "win32") {
    run("schtasks", ["/End", "/TN", "KuadorRunner"]);
    run("schtasks", ["/Delete", "/TN", "KuadorRunner", "/F"]);
  }
}

/** Reinicia el servicio (tras cambiar roles o repos). */
export function restart() {
  if (process.platform === "darwin") run("launchctl", ["kickstart", "-k", `gui/${process.getuid()}/${LABEL}`]);
  else if (process.platform === "linux") run("systemctl", ["--user", "restart", "kuador-runner.service"]);
  else if (process.platform === "win32") {
    run("schtasks", ["/End", "/TN", "KuadorRunner"]);
    run("schtasks", ["/Run", "/TN", "KuadorRunner"]);
  }
}

/**
 * Mientras haya trabajo, que la máquina no se duerma: caffeinate en Mac, systemd-inhibit en Linux. En Windows no hay
 * equivalente sin permisos: se avisa en la instalación.
 */
export function keepAwake(pid) {
  if (process.platform === "darwin") return spawnDetached("caffeinate", ["-i", "-w", String(pid)]);
  if (process.platform === "linux" && run("which", ["systemd-inhibit"]).status === 0) {
    return spawnDetached("systemd-inhibit", ["--what=sleep", "--why=Kuador: un agente está trabajando", "tail", `--pid=${pid}`, "-f", "/dev/null"]);
  }
  return null;
}

function spawnDetached(cmd, args) {
  const r = spawnSync("sh", ["-c", `${cmd} ${args.map((a) => `'${a.replace(/'/g, "'\\''")}'`).join(" ")} >/dev/null 2>&1 &`]);
  return r.status === 0;
}

/** Abre el enlace en el navegador si se puede (por SSH no hay: se muestra para copiarlo). */
export function openBrowser(url) {
  if (process.env.KUADOR_NO_BROWSER) return false;
  const cmd = { darwin: "open", win32: "start", linux: "xdg-open" }[process.platform];
  if (!cmd) return false;
  const r = process.platform === "win32" ? run("cmd", ["/c", "start", "", url]) : run(cmd, [url]);
  return r.status === 0;
}

const xml = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
