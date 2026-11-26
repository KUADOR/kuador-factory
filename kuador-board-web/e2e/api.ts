/** Prepara datos por la API (más rápido y estable que hacerlo a clics); lo que se prueba es lo que se ve después. */
const API = process.env.E2E_API_URL ?? "http://127.0.0.1:18091";
export const EMAIL = process.env.E2E_EMAIL ?? "prueba@e2e.test";
export const PASSWORD = process.env.E2E_PASSWORD ?? "clave-larga-de-prueba-1";

let token: string | undefined;

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  if (!token && path !== "/api/auth/login") {
    token = (await call<{ token: string }>("POST", "/api/auth/login", { email: EMAIL, password: PASSWORD })).token;
  }
  const res = await fetch(API + path, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text}`);
  return (text ? JSON.parse(text) : undefined) as T;
}

export interface Column {
  key: string;
  name: string;
  category: "TODO" | "IN_PROGRESS" | "DONE";
}

/**
 * Un tablero nuevo para una prueba. La clave lleva unas letras al azar: así una prueba que se repite (un reintento, o
 * correrlas dos veces contra el mismo entorno) no choca con lo que dejó la vez anterior.
 */
export async function board(prefix: string, name: string, templateKey = "TASKS"): Promise<{ key: string; columns: Column[] }> {
  const letters = Array.from({ length: 4 }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join("");
  const key = prefix + letters;
  await call("POST", "/api/projects", { key, name, templateKey });
  return { key, columns: (await call<{ columns: Column[] }>("GET", `/api/projects/${key}`)).columns };
}

export async function card(projectKey: string, input: { title: string; status: string; type?: string; labels?: string[]; priority?: string }): Promise<string> {
  return (await call<{ key: string }>("POST", `/api/projects/${projectKey}/issues`, { type: "TASK", ...input })).key;
}

/** Un proyecto del espacio (aquello sobre lo que se trabaja); el nombre lleva letras al azar para no chocar entre corridas. */
export async function subject(name: string): Promise<{ key: string; name: string }> {
  const letters = Array.from({ length: 4 }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join("");
  const full = `${name} ${letters}`;
  return { key: (await call<{ key: string }>("POST", "/api/subjects", { name: full, key: "P" + letters })).key, name: full };
}

/** Un tablero que trabaja en varios proyectos del espacio. */
export async function teamBoard(prefix: string, name: string, subjects: string[], templateKey = "TASKS"): Promise<{ key: string; columns: Column[] }> {
  const letters = Array.from({ length: 4 }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join("");
  const key = prefix + letters;
  await call("POST", "/api/projects", { key, name, templateKey, subjects });
  return { key, columns: (await call<{ columns: Column[] }>("GET", `/api/projects/${key}`)).columns };
}

export async function cardIn(projectKey: string, subjectKey: string, input: { title: string; status: string; type?: string }): Promise<string> {
  return (await call<{ key: string }>("POST", `/api/projects/${projectKey}/issues`, { type: "TASK", subjectKey, ...input })).key;
}
