import { fetchWithSession, kitQuery } from "@/lib/api";

/** Descarga del kit (.zip) con la sesión de quien lo pide: el navegador nunca llama a la API directo. */
export async function GET(request: Request) {
  const params = new URL(request.url).searchParams;
  const res = await fetchWithSession(`/api/agent-kit.zip?${kitQuery(params.get("client") ?? "claude-code", params.getAll("mcp"))}`);
  if (!res.ok) return new Response("No se pudo generar el kit.", { status: res.status });
  return new Response(res.body, {
    headers: {
      "Content-Type": "application/zip",
      "Content-Disposition": res.headers.get("Content-Disposition") ?? 'attachment; filename="kit-agentes.zip"',
      "Cache-Control": "no-store",
    },
  });
}
