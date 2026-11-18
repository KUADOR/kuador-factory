import { fetchWithSession } from "@/lib/api";

/**
 * Cambios en tiempo real (Server-Sent Events). El navegador nunca habla con la API: este handler abre
 * el stream con la sesión y lo reenvía. Si el navegador se va, se corta también hacia la API; si la API
 * corta (reinicio, tiempo máximo, red), se cierra limpio y el navegador reconecta solo.
 */
export async function GET(request: Request) {
  const project = new URL(request.url).searchParams.get("project");
  let res: Response;
  try {
    res = await fetchWithSession(`/api/live${project ? `?project=${encodeURIComponent(project)}` : ""}`, request.signal);
  } catch {
    // La API no responde: 503 hace que el cliente reintente más tarde (ver LiveUpdates).
    return new Response(null, { status: 503 });
  }
  if (!res.ok || !res.body) return new Response(null, { status: res.status === 200 ? 502 : res.status });

  const upstream = res.body.getReader();
  const body = new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        const { done, value } = await upstream.read();
        if (done) controller.close();
        else controller.enqueue(value);
      } catch {
        // La API cerró la conexión a medias: se termina el stream sin error para el navegador.
        try {
          controller.close();
        } catch {
          // Ya estaba cerrado.
        }
      }
    },
    cancel() {
      upstream.cancel().catch(() => {});
    },
  });

  return new Response(body, {
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      // no-transform: que nadie en el camino lo comprima ni lo acumule antes de mandarlo.
      "Cache-Control": "no-cache, no-transform",
      "X-Accel-Buffering": "no",
    },
  });
}
