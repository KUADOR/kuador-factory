/** Filtros de la auditoría en la URL → consulta para el API. */
export function auditQuery(params: { ver?: string; q?: string; desde?: string }, extra: Record<string, string> = {}): string {
  const q = new URLSearchParams(extra);
  if (params.ver !== "todo") q.set("category", "SECURITY");
  if (params.q?.trim()) q.set("q", params.q.trim());
  const days = params.desde === "todo" ? null : Number(params.desde ?? "30");
  if (days) q.set("from", new Date(Date.now() - days * 86_400_000).toISOString());
  q.set("limit", "50");
  return q.toString();
}
