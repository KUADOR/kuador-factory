import { fetchWithSession, kitQuery } from "@/lib/api";

/** Descarga del kit (.zip) con la sesión de quien lo pide: el navegador nunca llama a la API directo. */
export async function GET(request: Request) {
  const mcp = new URL(request.url).searchParams.getAll("mcp");
  const res = await fetchWithSession(`/api/claude-kit.zip?${kitQuery(mcp)}`);
  if (!res.ok) return new Response("No se pudo generar el kit.", { status: res.status });
  return new Response(res.body, {
    headers: {
      "Content-Type": "application/zip",
      "Content-Disposition": 'attachment; filename="kuador-claude-kit.zip"',
      "Cache-Control": "no-store",
    },
  });
}
