import type { NextConfig } from "next";

/** Cabeceras de seguridad en todas las páginas: nadie puede meter el tablero en un iframe (clickjacking). */
const securityHeaders = [
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
];

/**
 * Las rutas eran en español; ahora todas en inglés. Las viejas redirigen para siempre (308, conserva método y query):
 * enlaces de correos ya enviados, la GitHub App ya registrada, pagos en curso y marcadores.
 */
const OLD_ROUTES: [string, string][] = [
  ["/adjuntos/archivo/:id", "/attachments/file/:id"],
  ["/adjuntos/:path*", "/attachments/:path*"],
  ["/agentes-locales/:path*", "/local-agents/:path*"],
  ["/auditoria/exportar", "/audit/export"],
  ["/auditoria", "/audit"],
  ["/conexiones/github/app-creada", "/connections/github/app-created"],
  ["/conexiones/github/instalada", "/connections/github/installed"],
  ["/conexiones", "/connections"],
  ["/invitacion", "/invitation"],
  ["/recuperar", "/reset-password"],
  ["/producto", "/product"],
  ["/workspace/plan/pago", "/workspace/plan/payment"],
  ["/plataforma/auditoria", "/platform/audit"],
  ["/plataforma/ia", "/platform/ai"],
  ["/plataforma/pagos", "/platform/payments"],
  ["/plataforma/paquetes", "/platform/packages"],
  ["/plataforma/planes", "/platform/plans"],
  ["/plataforma/catalogo/plantillas", "/platform/catalog/templates"],
  ["/plataforma/catalogo/metodologias", "/platform/catalog/methodologies"],
  ["/plataforma/catalogo/piezas", "/platform/catalog/components"],
  ["/plataforma/catalogo/reglas", "/platform/catalog/rules"],
  ["/plataforma/catalogo/:path*", "/platform/catalog/:path*"],
  ["/plataforma", "/platform"],
  ["/catalog/plantillas", "/catalog/templates"],
  ["/catalog/metodologias", "/catalog/methodologies"],
  ["/catalog/piezas", "/catalog/components"],
  ["/catalog/reglas", "/catalog/rules"],
  ["/projects/:key/inicio", "/projects/:key/setup"],
  ["/projects/:key/oficina", "/projects/:key/office"],
  ["/projects/:key/settings/columnas", "/projects/:key/settings/columns"],
  ["/projects/:key/settings/equipo", "/projects/:key/settings/agents"],
  ["/projects/:key/settings/etapas", "/projects/:key/settings/stages"],
  ["/projects/:key/settings/metodologia", "/projects/:key/settings/methodology"],
  ["/projects/:key/settings/personas", "/projects/:key/settings/people"],
  ["/projects/:key/settings/repositorio", "/projects/:key/settings/repository"],
  ["/projects/:key/settings/transiciones", "/projects/:key/settings/transitions"],
];

const nextConfig: NextConfig = {
  poweredByHeader: false,
  // Servidor autocontenido para la imagen Docker (solo lo que usa la app, sin node_modules completo).
  output: "standalone",
  async redirects() {
    return OLD_ROUTES.map(([source, destination]) => ({ source, destination, permanent: true }));
  },
  async headers() {
    return [
      { source: "/:path*", headers: securityHeaders },
      // El service worker siempre fresco (si no, una versión vieja seguiría mostrando avisos con la lógica anterior).
      {
        source: "/sw.js",
        headers: [
          { key: "Cache-Control", value: "no-cache, no-store, must-revalidate" },
          { key: "Content-Type", value: "application/javascript; charset=utf-8" },
          { key: "Content-Security-Policy", value: "default-src 'self'; script-src 'self'" },
        ],
      },
    ];
  },
};

export default nextConfig;
