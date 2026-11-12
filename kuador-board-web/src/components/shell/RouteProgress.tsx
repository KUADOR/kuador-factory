"use client";

import { usePathname, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";

/**
 * Barra fina arriba del contenido mientras se abre otra página: aparece al instante al hacer clic en un enlace
 * interno y se va cuando la nueva página ya está. Complementa los esqueletos de carga: hay respuesta aunque el
 * servidor (o GitHub, o la IA) tarde.
 */
export function RouteProgress() {
  const path = usePathname();
  const search = useSearchParams();
  const here = `${path}?${search.toString()}`;
  const [pendingFrom, setPendingFrom] = useState<string | null>(null);
  // Se apaga sola al cambiar de ruta: la barra solo vive mientras la ruta de origen sigue siendo la actual.
  const pending = pendingFrom === here;

  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const a = (e.target as Element | null)?.closest?.("a");
      if (!a || a.target === "_blank" || a.hasAttribute("download")) return;
      const url = new URL(a.href, window.location.href);
      if (url.origin !== window.location.origin) return;
      if (url.pathname === window.location.pathname && url.search === window.location.search) return;
      setPendingFrom(`${window.location.pathname}?${new URLSearchParams(window.location.search).toString()}`);
    };
    document.addEventListener("click", onClick, true);
    return () => document.removeEventListener("click", onClick, true);
  }, []);

  if (!pending) return null;
  return (
    <div role="progressbar" aria-label="Cargando la página" className="pointer-events-none absolute inset-x-0 top-0 z-40 h-0.5 overflow-hidden">
      <div className="h-full w-1/3 bg-sol motion-safe:animate-[route-progress_1.1s_ease-in-out_infinite]" />
    </div>
  );
}
