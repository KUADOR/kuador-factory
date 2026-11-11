import { fetchWithSession } from "@/lib/api";

/**
 * Cambios en tiempo real (Server-Sent Events). El navegador nunca habla con la API: este handler abre
 * el stream con la sesión y lo reenvía tal cual. Si el navegador se va, se corta también hacia la API.
 */
export async function GET(request: Request) {
  const project = new URL(request.url).searchParams.get("project");
  const res = await fetchWithSession(`/api/live${project ? `?project=${encodeURIComponent(project)}` : ""}`, request.signal);
  if (!res.ok || !res.body) return new Response(null, { status: res.status === 200 ? 502 : res.status });
  return new Response(res.body, {
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      // no-transform: que nadie en el camino lo comprima ni lo acumule antes de mandarlo.
      "Cache-Control": "no-cache, no-transform",
      "X-Accel-Buffering": "no",
    },
  });
}
