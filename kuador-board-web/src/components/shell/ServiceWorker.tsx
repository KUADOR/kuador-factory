"use client";

import { useEffect } from "react";

/** Registra el service worker (app instalable y notificaciones push). No hace nada donde el navegador no lo soporta. */
export function ServiceWorker() {
  useEffect(() => {
    if (!("serviceWorker" in navigator)) return;
    navigator.serviceWorker.register("/sw.js", { scope: "/", updateViaCache: "none" }).catch(() => {});
  }, []);
  return null;
}
