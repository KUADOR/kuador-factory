import { fetchWithSession } from "@/lib/api";

/** Sube un adjunto a la tarjeta {key}: reenvía el formulario tal cual a la API, con la sesión. */
export async function POST(request: Request, { params }: { params: Promise<{ key: string }> }) {
  const { key } = await params;
  const res = await fetchWithSession(`/api/issues/${encodeURIComponent(key)}/attachments`, request.signal, {
    method: "POST",
    headers: { "Content-Type": request.headers.get("Content-Type") ?? "" },
    body: request.body,
    duplex: "half",
  });
  return new Response(res.body, { status: res.status, headers: { "Content-Type": res.headers.get("Content-Type") ?? "application/json" } });
}
