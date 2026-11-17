import type { MetadataRoute } from "next";

/**
 * La web como app instalable (PWA): ícono en la pantalla de inicio, se abre sin barra del navegador, recibe
 * notificaciones push y aparece en «Compartir» del teléfono para crear una tarjeta con lo compartido.
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "Kuador Go",
    short_name: "Kuador",
    description: "Tu tablero de trabajo: personas y agentes de IA en el mismo flujo.",
    id: "/",
    start_url: "/decisions",
    scope: "/",
    display: "standalone",
    background_color: "#0a0e14",
    theme_color: "#0a0e14",
    lang: "es",
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      { src: "/icons/maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
    shortcuts: [
      { name: "Mis decisiones", url: "/decisions", icons: [{ src: "/icons/icon-192.png", sizes: "192x192" }] },
      { name: "Capturar una idea", url: "/capture", icons: [{ src: "/icons/icon-192.png", sizes: "192x192" }] },
      { name: "Pulso del día", url: "/pulse", icons: [{ src: "/icons/icon-192.png", sizes: "192x192" }] },
    ],
    // «Compartir → Kuador» desde WhatsApp, el correo o el navegador: abre la captura con el texto compartido.
    share_target: { action: "/capture", method: "GET", params: { title: "title", text: "text", url: "url" } },
  } as MetadataRoute.Manifest;
}
