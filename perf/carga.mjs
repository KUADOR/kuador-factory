#!/usr/bin/env node
// Prueba de carga de la API del tablero. Sin dependencias (Node 22).
//
// Siembra un tablero con muchas tarjetas (casi todas terminadas, como un tablero con historia) y mide, con varias
// personas a la vez, lo que más se usa: abrir el tablero, la lista, una tarjeta, comentar; y cuánto tarda un cambio en
// llegar a todos los que tienen el tablero abierto (tiempo real).
//
//   node perf/carga.mjs                         contra el entorno de pruebas (E2E_KEEP=1 pnpm e2e lo deja arriba)
//   CARDS=5000 USERS=50 SECONDS=20 LIVE=200 node perf/carga.mjs
//   BOARD=CARXXXX USERS=5 node perf/carga.mjs   repite la medición sobre un tablero ya sembrado
//
// Nunca contra producción: crea un tablero y miles de tarjetas.

const API = process.env.E2E_API_URL ?? "http://127.0.0.1:18091";
const EMAIL = process.env.E2E_EMAIL ?? "prueba@e2e.test";
const PASSWORD = process.env.E2E_PASSWORD ?? "clave-larga-de-prueba-1";
const CARDS = Number(process.env.CARDS ?? 5000);
const USERS = Number(process.env.USERS ?? 50);
const SECONDS = Number(process.env.SECONDS ?? 15);
const LIVE = Number(process.env.LIVE ?? 200);

if (!/^https?:\/\/(127\.0\.0\.1|localhost)(:|\/|$)/.test(API) && process.env.CARGA_FUERA_DE_LOCAL !== "si") {
  console.error(`Esto crea miles de tarjetas: solo corre contra una API local. (${API})`);
  process.exit(2);
}

let token;
async function call(method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : undefined;
}

/** Corre `work(i)` para i en [0, total) con `width` a la vez. */
async function pool(total, width, work) {
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(width, total) }, async () => {
    while (next < total) await work(next++);
  }));
}

const percentile = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))] ?? 0;

/** `USERS` personas pidiendo lo mismo sin parar durante `SECONDS`. */
async function measure(name, request) {
  const times = [];
  let errors = 0;
  let bytes = 0;
  const until = Date.now() + SECONDS * 1000;
  await Promise.all(Array.from({ length: USERS }, async (_, user) => {
    for (let i = 0; Date.now() < until; i++) {
      const start = performance.now();
      try {
        const res = await request(user, i);
        bytes = Math.max(bytes, (await res.arrayBuffer()).byteLength);
        if (!res.ok) errors++;
      } catch {
        errors++;
      }
      times.push(performance.now() - start);
    }
  }));
  times.sort((a, b) => a - b);
  return {
    name,
    perSecond: Math.round(times.length / SECONDS),
    p50: Math.round(percentile(times, 50)),
    p95: Math.round(percentile(times, 95)),
    p99: Math.round(percentile(times, 99)),
    kb: Math.round(bytes / 1024),
    errors,
  };
}

const get = (path) => fetch(API + path, { headers: { Authorization: `Bearer ${token}` } });

/** Abre `LIVE` conexiones de tiempo real, hace un cambio y mide cuánto tarda en llegarle a cada una. */
async function liveFanOut(project, issueKey) {
  const controller = new AbortController();
  const arrivals = [];
  let ready = 0;
  let changedAt = 0;
  let lastEventAt = performance.now();
  const streams = Array.from({ length: LIVE }, async () => {
    const res = await fetch(`${API}/api/live?project=${project}`, { headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" }, signal: controller.signal });
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let seenReady = false;
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) return;
        const text = decoder.decode(value);
        if (!seenReady && text.includes("event:listo")) {
          seenReady = true;
          ready++;
        }
        if (text.includes("event:cambio")) {
          lastEventAt = performance.now();
          if (changedAt) {
            arrivals.push(performance.now() - changedAt);
            return;
          }
        }
      }
    } catch {
      // cortada al terminar
    }
  });
  const opened = performance.now();
  while (ready < LIVE && performance.now() - opened < 20_000) await new Promise((r) => setTimeout(r, 50));
  const connectMs = Math.round(performance.now() - opened);
  // Los casos anteriores dejaron avisos en camino: se espera a que terminen de llegar para medir solo el cambio propio.
  const draining = performance.now();
  while (performance.now() - lastEventAt < 1500 && performance.now() - draining < 180_000) await new Promise((r) => setTimeout(r, 100));
  const backlogMs = Math.max(0, Math.round(lastEventAt - draining));
  changedAt = performance.now();
  await call("POST", `/api/issues/${issueKey}/comments`, { body: "Cambio para medir el tiempo real." });
  const waited = performance.now();
  while (arrivals.length < ready && performance.now() - waited < 10_000) await new Promise((r) => setTimeout(r, 20));
  controller.abort();
  await Promise.allSettled(streams);
  arrivals.sort((a, b) => a - b);
  return { connected: ready, connectMs, backlogMs, received: arrivals.length, p50: Math.round(percentile(arrivals, 50)), max: Math.round(arrivals.at(-1) ?? 0) };
}

token = (await call("POST", "/api/auth/login", { email: EMAIL, password: PASSWORD })).token;
// BOARD=CLAVE reutiliza un tablero ya sembrado (para repetir la medición con otra cantidad de personas).
const reuse = process.env.BOARD;
const key = reuse ?? "CAR" + Array.from({ length: 4 }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join("");
if (!reuse) await call("POST", "/api/projects", { key, name: "Prueba de carga", templateKey: "TASKS" });
const columns = (await call("GET", `/api/projects/${key}`)).columns;
const done = columns.find((c) => c.category === "DONE").key;
const open = columns.filter((c) => c.category !== "DONE").map((c) => c.key);

console.error(reuse ? `Reutilizando ${key}…` : `Sembrando ${CARDS} tarjetas en ${key}…`);
const seedStart = Date.now();
await pool(reuse ? 0 : CARDS, 16, (i) =>
  call("POST", `/api/projects/${key}/issues`, {
    title: `Pedido ${String(i).padStart(5, "0")} de la tienda ${i % 37}`,
    description: "Detalle del pedido, con la dirección de entrega y lo que pidió el cliente. ".repeat(4),
    type: "TASK",
    // 1 de cada 50 sigue en curso: un tablero con historia tiene casi todo terminado.
    status: i % 50 === 0 ? open[i % open.length] : done,
    labels: i % 9 === 0 ? ["urgente"] : [],
  }));
console.error(`Sembrado en ${Math.round((Date.now() - seedStart) / 1000)} s. Midiendo con ${USERS} personas, ${SECONDS} s por caso…`);

const results = [];
results.push(await measure("Abrir el tablero (nuevo: sin historial)", () => get(`/api/projects/${key}/board`)));
results.push(await measure("Listado completo (como abría antes)", () => get(`/api/projects/${key}/issues`)));
results.push(await measure("Lista: una página de 50", (u, i) => get(`/api/projects/${key}/issues/search?size=50&page=${(u + i) % 20}`)));
results.push(await measure("Lista: buscar por texto", (u) => get(`/api/projects/${key}/issues/search?q=tienda%20${u % 37}`)));
results.push(await measure("Lista: filtrar por etiqueta y ordenar", () => get(`/api/projects/${key}/issues/search?label=urgente&sort=title&dir=asc`)));
results.push(await measure("Abrir una tarjeta", (u, i) => get(`/api/issues/${key}-${1 + ((u * 131 + i) % CARDS)}`)));
results.push(await measure("Configuración del tablero", () => get(`/api/projects/${key}`)));
results.push(await measure("Comentar una tarjeta", (u, i) =>
  fetch(`${API}/api/issues/${key}-${1 + ((u * 17 + i) % CARDS)}/comments`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ body: "Un comentario de la prueba de carga." }),
  })));
const live = await liveFanOut(key, `${key}-1`);

console.log(`\n## Prueba de carga — ${CARDS} tarjetas, ${USERS} personas a la vez, ${SECONDS} s por caso\n`);
console.log("| Caso | Pedidos/s | p50 ms | p95 ms | p99 ms | Respuesta KB | Errores |");
console.log("|---|---:|---:|---:|---:|---:|---:|");
for (const r of results) console.log(`| ${r.name} | ${r.perSecond} | ${r.p50} | ${r.p95} | ${r.p99} | ${r.kb} | ${r.errors} |`);
console.log(`\n**Tiempo real:** ${live.connected} de ${LIVE} conexiones abiertas en ${live.connectMs} ms; un cambio le llegó a ${live.received}` +
  ` (mitad en ${live.p50} ms, la última en ${live.max} ms). Los avisos pendientes de los casos anteriores terminaron de llegar ${live.backlogMs} ms después de abrir.`);
