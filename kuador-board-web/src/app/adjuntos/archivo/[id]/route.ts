import { fetchWithSession } from "@/lib/api";

/** Descarga (o muestra, con ?inline=1 para imágenes y PDF) un adjunto con la sesión de quien lo pide. */
export async function GET(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const inline = new URL(request.url).searchParams.get("inline") === "1";
  const res = await fetchWithSession(`/api/attachments/${encodeURIComponent(id)}/content${inline ? "?inline=true" : ""}`, request.signal);
  if (!res.ok) return new Response("No se pudo abrir el adjunto.", { status: res.status });
  const headers = new Headers({ "Cache-Control": "private, max-age=300", "X-Content-Type-Options": "nosniff" });
  for (const h of ["Content-Type", "Content-Disposition", "Content-Length"]) {
    const v = res.headers.get(h);
    if (v) headers.set(h, v);
  }
  return new Response(res.body, { headers });
}
