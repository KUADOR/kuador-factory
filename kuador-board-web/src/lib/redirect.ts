import "server-only";

/**
 * Redirección dentro del tablero con una ruta relativa. Detrás del proxy (Traefik), `request.url` trae el
 * host interno del contenedor (0.0.0.0:3600) y no el dominio público: el navegador resuelve la ruta
 * contra la dirección que ya tiene abierta.
 */
export function redirectTo(path: string, params: Record<string, string> = {}): Response {
  const query = new URLSearchParams(params).toString();
  return new Response(null, { status: 303, headers: { Location: query ? `${path}?${query}` : path } });
}
