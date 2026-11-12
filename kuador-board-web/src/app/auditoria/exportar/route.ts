import { fetchWithSession } from "@/lib/api";
import { auditQuery } from "@/lib/audit";

/** Descarga la auditoría en CSV con la sesión de la persona (la exportación también queda auditada). */
export async function GET(request: Request) {
  // Los mismos filtros de la pantalla (ver, q, desde); la fecha de inicio se calcula aquí.
  const url = new URL(request.url).searchParams;
  const query = auditQuery({ ver: url.get("ver") ?? undefined, q: url.get("q") ?? undefined, desde: url.get("desde") ?? undefined }).replace(/&?limit=\d+/, "");
  const res = await fetchWithSession(`/api/workspace/audit/export?${query}`, request.signal);
  return new Response(res.body, {
    status: res.status,
    headers: {
      "Content-Type": res.headers.get("Content-Type") ?? "text/csv",
      "Content-Disposition": res.headers.get("Content-Disposition") ?? 'attachment; filename="auditoria.csv"',
    },
  });
}
