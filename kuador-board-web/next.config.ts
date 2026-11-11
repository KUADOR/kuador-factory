import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  poweredByHeader: false,
  // Servidor autocontenido para la imagen Docker (solo lo que usa la app, sin node_modules completo).
  output: "standalone",
};

export default nextConfig;
