/** Cliente de la API del tablero (rutas del runner). */
export const VERSION = "0.1.4";

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export function api(server, token) {
  const base = server.replace(/\/+$/, "");
  const call = async (method, path, body, { allow = [] } = {}) => {
    const res = await fetch(base + path, {
      method,
      headers: {
        "Content-Type": "application/json",
        "User-Agent": `kuador-runner/${VERSION}`,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(30_000),
    });
    if (res.ok || allow.includes(res.status)) {
      const text = await res.text();
      return { status: res.status, body: text ? JSON.parse(text) : null };
    }
    let detail = "";
    try {
      const p = await res.json();
      detail = p.detail || p.title || "";
    } catch {
      // Sin cuerpo.
    }
    if (res.status === 401) throw new ApiError(401, "El tablero no reconoce esta máquina (la conexión venció o la revocaron). Vuelve a ejecutar: npx @kuador-npm/runner install");
    throw new ApiError(res.status, detail || `El tablero respondió ${res.status} en ${path}.`);
  };
  return {
    base,
    health: () => fetch(base + "/livez", { signal: AbortSignal.timeout(10_000) }).then((r) => r.ok, () => false),
    pair: (machineName) => call("POST", "/api/runner/pair", { machineName }).then((r) => r.body),
    pairToken: (deviceCode) => call("POST", "/api/runner/pair/token", { deviceCode }, { allow: [202, 410] }),
    setup: () => call("GET", "/api/runner/setup").then((r) => r.body),
    heartbeat: (body) => call("PUT", "/api/runner/heartbeat", body).then((r) => r.body),
    claim: (machineId) => call("POST", "/api/runner/claim", { machineId }).then((r) => (r.status === 204 ? null : r.body)),
    claimJob: (machineId) => call("POST", "/api/runner/jobs/claim", { machineId }).then((r) => (r.status === 204 ? null : r.body)),
    completeJob: (machineId, id, text, error) =>
      call("POST", `/api/runner/jobs/${encodeURIComponent(id)}/complete`, { machineId, text: text?.slice(0, 200_000), error: error?.slice(0, 480) }),
    renew: (machineId, key) => call("POST", `/api/runner/leases/${encodeURIComponent(key)}/renew`, { machineId }, { allow: [409] }).then((r) => r.status === 200),
    release: (machineId, key, outcome, message) =>
      call("POST", `/api/runner/leases/${encodeURIComponent(key)}/release`, { machineId, outcome, message: message?.slice(0, 480) }),
    /** Avisos en tiempo real: llama a onSignal con cada «trabajo». Termina cuando se corta la conexión. */
    async events(onSignal, signal) {
      const res = await fetch(base + "/api/runner/events", {
        headers: { Accept: "text/event-stream", Authorization: `Bearer ${token}`, "User-Agent": `kuador-runner/${VERSION}` },
        signal,
      });
      if (!res.ok || !res.body) throw new ApiError(res.status, `No se pudo escuchar al tablero (${res.status}).`);
      const decoder = new TextDecoder();
      let buffer = "";
      for await (const chunk of res.body) {
        buffer += decoder.decode(chunk, { stream: true });
        let index;
        while ((index = buffer.indexOf("\n\n")) >= 0) {
          const block = buffer.slice(0, index);
          buffer = buffer.slice(index + 2);
          if (/^event:\s*trabajo/m.test(block)) onSignal();
        }
      }
    },
  };
}
