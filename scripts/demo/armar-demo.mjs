#!/usr/bin/env node
// Arma el espacio de demostración «Kuador Demo» con 10 tableros, todo por la API (las mismas reglas que la web).
//
//   KUADOR_API=https://go-api.kuador.com \
//   PLATFORM_EMAIL=tu@correo PLATFORM_PASSWORD=... DEMO_PASSWORD=... \
//   node scripts/demo/armar-demo.mjs
//
// Quien administra la plataforma crea el espacio (sin código al correo: la cuenta de demo no recibe correos) y le da
// el plan Factory sin vencimiento. Después, con la cuenta de demo, crea agentes, plantillas, equipos, proyectos,
// tableros, rutinas (apagadas) y tarjetas de ejemplo. Los agentes quedan en pausa: el tope mensual de IA es 0 y
// trabajan desde una PC (LOCAL), así nada gasta IA hasta que alguien los encienda.
//
// Se puede volver a correr: lo que ya existe se salta (un tablero que ya existe no se vuelve a llenar).

import { boards } from "./tableros.mjs";

const API = (process.env.KUADOR_API ?? "http://127.0.0.1:8080").replace(/\/+$/, "");
const DEMO = {
  companyName: process.env.DEMO_COMPANY ?? "Kuador Demo",
  slug: process.env.DEMO_SLUG ?? "kuador-demo",
  ownerName: process.env.DEMO_OWNER ?? "Equipo Demo",
  email: process.env.DEMO_EMAIL ?? "info-demo@kuador.com",
  password: process.env.DEMO_PASSWORD,
  description: "Espacio de demostración: 10 tableros para automatizar una oficina.",
  brandColor: "#6d28d9",
  plan: "FACTORY",
};
const TIMEZONE = process.env.DEMO_TIMEZONE ?? "America/Guayaquil";

async function call(token, method, path, body, { allow = [] } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data = text ? safeJson(text) : undefined;
  if (!res.ok && !allow.includes(res.status)) throw new Error(`${method} ${path} → ${res.status} ${text.slice(0, 400)}`);
  return { status: res.status, data };
}

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

const login = async (email, password) => (await call(null, "POST", "/api/auth/login", { email, password })).data.token;
const log = (...args) => console.log(...args);

async function platformStep() {
  const token = process.env.PLATFORM_TOKEN ?? (process.env.PLATFORM_EMAIL ? await login(process.env.PLATFORM_EMAIL, process.env.PLATFORM_PASSWORD) : null);
  if (!token) throw new Error("Falta PLATFORM_EMAIL y PLATFORM_PASSWORD (o PLATFORM_TOKEN) de quien administra la plataforma.");
  const created = await call(token, "POST", "/api/platform/workspaces", DEMO, { allow: [409] });
  log(created.status === 409 ? `· El espacio ya existía (${DEMO.email}).` : `✓ Espacio «${DEMO.companyName}» creado.`);
  const workspace = (await call(token, "GET", "/api/platform/workspaces")).data.find((w) => w.slug === DEMO.slug);
  if (!workspace) throw new Error(`No encontré el espacio ${DEMO.slug}.`);
  await call(token, "PUT", `/api/platform/workspaces/${workspace.workspaceId}/license`, { plan: DEMO.plan, status: "ACTIVE", trialEndsAt: null, paidUntil: null });
  log(`✓ Plan ${DEMO.plan} activo, sin vencimiento.`);
}

async function upsertCatalog(token, type, entry) {
  const existing = await call(token, "GET", `/api/catalog/${type}/${entry.key}`, undefined, { allow: [404] });
  if (existing.status === 200 && existing.data && existing.data.builtIn === false) return "ya existía";
  await call(token, "PUT", `/api/catalog/${type}/${entry.key}`, existing.status === 200 ? { ...entry, version: existing.data.version } : entry);
  return "listo";
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * En pausa antes de la primera tarjeta: el tablero suma sus agentes un instante después de crearse (y fuera de software
 * los deja trabajando en el servidor). Se espera a que estén todos y se pasan a LOCAL hasta que la API lo confirme.
 */
async function pause(token, b) {
  const wanted = new Set(b.roles.map((r) => r.key));
  for (let i = 0; i < 40; i++) {
    const team = (await call(token, "GET", `/api/projects/${b.key}/team`)).data.filter((a) => a.inTeam && wanted.has(a.role));
    const serving = team.filter((a) => a.mode !== "LOCAL");
    if (team.length === wanted.size && serving.length === 0 && i > 0) return;
    for (const a of serving) await call(token, "PUT", `/api/agents/${a.id}/profile`, { mode: "LOCAL", model: a.model ?? null, effort: a.effort ?? null });
    await wait(500);
  }
  throw new Error(`${b.name}: no pude dejar sus agentes en pausa.`);
}

async function buildBoard(token, b, teams, subjects) {
  for (const r of b.roles) await upsertCatalog(token, "roles", r);
  const templateKey = `DEMO_${b.key}`;
  await upsertCatalog(token, "workflow-templates", { key: templateKey, name: b.name, description: b.goal, agents: b.roles.map((r) => r.key), ...b.template });

  let team = teams.find((t) => t.name === b.team);
  if (!team) team = (await call(token, "POST", "/api/teams", { name: b.team })).data;
  if (!subjects.some((s) => s.key === b.project.key)) await call(token, "POST", "/api/subjects", b.project);

  const existing = await call(token, "GET", `/api/projects/${b.key}`, undefined, { allow: [404, 403] });
  if (existing.status === 200) {
    log(`· ${b.name}: el tablero ya existía, no lo vuelvo a llenar.`);
    return;
  }
  await call(token, "POST", "/api/projects", { key: b.key, name: b.name, description: b.goal, templateKey, subjects: [b.project.key], teamId: team.id });
  await call(token, "PUT", `/api/projects/${b.key}/goal`, { goal: b.goal });

  await pause(token, b);

  for (const r of b.routines) {
    await call(token, "POST", `/api/projects/${b.key}/routines`, { timezone: TIMEZONE, enabled: false, skipIfOpen: true, ...r });
  }

  let count = 0;
  for (const c of b.cards) {
    const { tasks, ...card } = c;
    const created = (await call(token, "POST", `/api/projects/${b.key}/issues`, { type: "TASK", subjectKey: b.project.key, ...card })).data;
    count++;
    for (const title of tasks ?? []) {
      await call(token, "POST", `/api/projects/${b.key}/issues`, { type: "TASK", title, status: card.status, parentKey: created.key, subjectKey: b.project.key });
      count++;
    }
  }
  log(`✓ ${b.name}: ${b.roles.length} agente(s), ${b.routines.length} rutina(s) apagada(s), ${count} tarjeta(s).`);
}

async function main() {
  if (!DEMO.password) throw new Error("Falta DEMO_PASSWORD: la clave con la que entrará la cuenta de demostración.");
  log(`API: ${API}`);
  if (process.env.SKIP_PLATFORM !== "1") await platformStep();
  const token = await login(DEMO.email, DEMO.password);
  // Tope mensual de IA en 0: aunque alguien encienda un agente, no gasta hasta que se suba el tope.
  const budget = await call(token, "PUT", "/api/workspace/ai/budget", { budgetUsd: 0 }, { allow: [422] });
  log(budget.status === 422
    ? "✓ El espacio no tiene clave de IA: ningún agente puede trabajar hasta que se guarde una (en pausa)."
    : "✓ Tope de IA en USD 0 (agentes en pausa).");
  const teams = (await call(token, "GET", "/api/teams")).data;
  const subjects = (await call(token, "GET", "/api/subjects")).data;
  const only = process.env.ONLY ? process.env.ONLY.split(",") : null;
  for (const b of boards) {
    if (only && !only.includes(b.key)) continue;
    await buildBoard(token, b, teams, subjects);
  }
  log(`\nListo. Entra con ${DEMO.email} en la web.`);
}

main().catch((e) => {
  console.error("✗", e.message);
  process.exit(1);
});
