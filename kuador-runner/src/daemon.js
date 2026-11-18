import { appendFileSync, createWriteStream, existsSync, rmSync } from "node:fs";
import { join } from "node:path";

import { api, VERSION } from "./api.js";
import { ensureDirs, LOGS, PAUSE, requireConfig, save, SECRETS, writeState } from "./config.js";
import { keepAwake, osName } from "./service.js";
import { launch, outcome } from "./tools.js";
import { cleanup, prepare } from "./workdir.js";

const HEARTBEAT_MS = 30_000;
const RENEW_MS = 60_000;
const FALLBACK_MS = 5 * 60_000;

function log(message) {
  const line = `${new Date().toISOString()} ${message}`;
  console.log(line);
  try {
    appendFileSync(join(LOGS, "runner.log"), line + "\n");
  } catch {
    // Sin registro en disco: queda en la salida del servicio.
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * El runner trabajando: escucha al tablero, pide tarjetas mientras tenga lugar, lanza el cliente de IA en la carpeta de
 * cada una, renueva su préstamo y la libera al terminar. Si la suscripción llega a su límite, se pausa hasta que se renueve.
 */
export async function start() {
  ensureDirs();
  const config = requireConfig();
  const client = api(config.server, config.token);
  const jobs = new Map();
  let machine = { paused: false, capacity: config.capacity ?? 2, roles: config.roles ?? [] };
  let pausedUntil = 0;
  let pumping = false;
  let timer = null;
  let stopping = false;

  const state = () =>
    writeState({
      updatedAt: new Date().toISOString(),
      paused: machine.paused,
      localPaused: existsSync(PAUSE),
      pausedUntil: pausedUntil > Date.now() ? new Date(pausedUntil).toISOString() : null,
      capacity: machine.capacity,
      roles: machine.roles,
      jobs: [...jobs.values()].map((j) => ({ issue: j.claim.issueKey, title: j.claim.title, role: j.claim.roleName, since: j.started })),
    });

  const heartbeat = async (register = false) => {
    try {
      machine = await client.heartbeat({
        machineId: config.machineId,
        name: config.name,
        os: osName(),
        tool: config.tool ?? "claude",
        roles: config.roles,
        capacity: config.capacity,
        version: VERSION,
        register,
      });
      // El tablero manda: ahí se pueden cambiar roles, capacidad o pausar la máquina.
      if (machine.roles.join() !== (config.roles ?? []).join() || machine.capacity !== config.capacity) {
        config.roles = machine.roles;
        config.capacity = machine.capacity;
        save(config);
      }
      state();
    } catch (e) {
      log(`Sin señal con el tablero: ${e.message}`);
    }
  };

  const canTake = () => !stopping && !machine.paused && !existsSync(PAUSE) && Date.now() >= pausedUntil && jobs.size < machine.capacity;

  /** Varios avisos juntos cuentan como uno; con más trabajo en curso se espera un poco más (así se reparte con el equipo). */
  const schedule = () => {
    clearTimeout(timer);
    timer = setTimeout(pump, 2000 + jobs.size * 3000);
  };

  const pump = async () => {
    if (pumping) return;
    pumping = true;
    try {
      while (canTake()) {
        const claim = await client.claim(config.machineId);
        if (!claim) break;
        run(claim);
      }
    } catch (e) {
      log(`No se pudo pedir trabajo: ${e.message}`);
    } finally {
      pumping = false;
    }
  };

  const run = (claim) => {
    const started = new Date().toISOString();
    let work;
    try {
      work = prepare(config, claim);
    } catch (e) {
      log(`${claim.issueKey}: no se pudo preparar la carpeta de trabajo: ${e.message}`);
      client.release(config.machineId, claim.issueKey, "FAILED", `No se pudo preparar la carpeta: ${e.message}`).catch(() => {});
      return;
    }
    const logFile = join(LOGS, `${claim.issueKey}-${started.replace(/[:.]/g, "-")}.log`);
    const stream = createWriteStream(logFile);
    log(`${claim.issueKey} → ${claim.agentName} (${claim.roleName}) en ${work.dir}${work.repo ? " (copia del repo)" : ""}`);
    const { child, done } = launch(config, claim, work.dir, stream);
    const job = { claim, started, child, lost: false, timedOut: false };
    jobs.set(claim.issueKey, job);
    state();
    if (config.keepAwake !== false) keepAwake(child.pid);

    const renew = setInterval(async () => {
      try {
        if (!(await client.renew(config.machineId, claim.issueKey))) {
          // La tarjeta ya no es de esta máquina (la movieron o la tomó otra): se corta.
          job.lost = true;
          log(`${claim.issueKey}: el préstamo ya no es de esta máquina; se detiene.`);
          child.kill();
        }
      } catch (e) {
        log(`${claim.issueKey}: no se pudo renovar (${e.message}); se reintenta.`);
      }
    }, RENEW_MS);
    const limit = setTimeout(() => {
      job.timedOut = true;
      log(`${claim.issueKey}: pasó el tiempo máximo (${config.timeoutMinutes ?? 60} min); se detiene.`);
      child.kill();
    }, (config.timeoutMinutes ?? 60) * 60_000);

    done.then(async (result) => {
      clearInterval(renew);
      clearTimeout(limit);
      stream.end();
      rmSync(join(SECRETS, `${claim.issueKey}.json`), { force: true });
      jobs.delete(claim.issueKey);
      if (!job.lost && !stopping) {
        const r = job.timedOut ? { outcome: "TIMEOUT", message: `Pasó el tiempo máximo de ${config.timeoutMinutes ?? 60} minutos.` } : outcome(result);
        if (r.outcome === "RATE_LIMITED") {
          pausedUntil = r.resumeAt.getTime();
          log(`La suscripción llegó a su límite: pausa hasta ${r.resumeAt.toLocaleString()}.`);
          setTimeout(schedule, Math.max(0, pausedUntil - Date.now()) + 1000);
        }
        log(`${claim.issueKey}: ${r.outcome}${r.message ? ` · ${r.message.slice(0, 160)}` : ""}`);
        await client.release(config.machineId, claim.issueKey, r.outcome, r.message).catch((e) => log(`${claim.issueKey}: no se pudo liberar: ${e.message}`));
      }
      state();
      schedule();
    });
  };

  const listen = async () => {
    let wait = 5000;
    while (!stopping) {
      const ac = new AbortController();
      try {
        await client.events(() => schedule(), ac.signal);
        wait = 5000;
      } catch (e) {
        if (e.status === 401) {
          log(e.message);
          process.exit(2);
        }
        log(`Se cortó la escucha del tablero (${e.message}); reconecta en ${wait / 1000} s.`);
      }
      await sleep(wait);
      wait = Math.min(wait * 2, 60_000);
    }
  };

  const shutdown = async () => {
    if (stopping) return;
    stopping = true;
    log("Deteniendo: se liberan las tarjetas en curso.");
    await Promise.all(
      [...jobs.values()].map(async (j) => {
        j.child.kill();
        await client.release(config.machineId, j.claim.issueKey, "CANCELLED", "Se detuvo el runner.").catch(() => {});
      }),
    );
    process.exit(0);
  };
  process.on("SIGTERM", shutdown);
  process.on("SIGINT", shutdown);

  log(`Kuador runner ${VERSION} · ${config.name} · roles: ${(config.roles ?? []).join(", ")} · hasta ${config.capacity} a la vez`);
  await heartbeat(Boolean(config.needsRegister));
  if (config.needsRegister) {
    delete config.needsRegister;
    save(config);
  }
  setInterval(() => heartbeat(), HEARTBEAT_MS);
  setInterval(schedule, FALLBACK_MS);
  setInterval(() => cleanup(config, new Set(jobs.keys())), 6 * 3600_000);
  listen();
  schedule();
}
