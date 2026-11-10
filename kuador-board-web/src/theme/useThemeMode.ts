"use client";

import { useSyncExternalStore } from "react";

/** Tema actual de la página, siguiendo la clase `app-dark` (el botón de tema la cambia sin recargar). */
export function useThemeMode(): "dark" | "light" {
  return useSyncExternalStore(
    (onChange) => {
      const observer = new MutationObserver(onChange);
      observer.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
      return () => observer.disconnect();
    },
    () => (document.documentElement.classList.contains("app-dark") ? "dark" : "light"),
    () => "dark",
  );
}
