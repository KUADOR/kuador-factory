"use client";

import { useRouter } from "next/navigation";
import { createContext, useContext, useEffect, useRef, useState } from "react";

type Change = { proyecto: string; issue: string | null; tipo: string; actor: string | null };

/** Cuántos cambios llegaron por issue: los paneles que cargan datos propios lo usan para recargar. */
const LiveContext = createContext<Record<string, number>>({});

/** Se incrementa cada vez que cambia ese issue (comentario, movimiento, documento…). */
export function useLiveVersion(issueKey: string): number {
  return useContext(LiveContext)[issueKey] ?? 0;
}

/**
 * Escucha los cambios del proyecto y vuelve a pedir los datos de la página. Agrupa los avisos que
 * llegan juntos y, con la pestaña oculta, espera a que vuelva a verse para recargar una sola vez.
 */
export function LiveUpdates({ projectKey, children }: { projectKey: string; children: React.ReactNode }) {
  const router = useRouter();
  const [versions, setVersions] = useState<Record<string, number>>({});
  const pending = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    const refresh = () => {
      if (document.hidden) {
        pending.current = true;
        return;
      }
      clearTimeout(timer.current);
      timer.current = setTimeout(() => {
        pending.current = false;
        router.refresh();
      }, 250);
    };
    const source = new EventSource(`/live?project=${encodeURIComponent(projectKey)}`);
    let connected = false;
    source.addEventListener("listo", () => {
      // Al reconectar pudo perderse algo mientras estaba cortado: se recarga por las dudas.
      if (connected) refresh();
      connected = true;
    });
    source.addEventListener("cambio", (e) => {
      try {
        const change = JSON.parse((e as MessageEvent<string>).data) as Change;
        if (change.issue) setVersions((v) => ({ ...v, [change.issue!]: (v[change.issue!] ?? 0) + 1 }));
      } catch {
        // Aviso ilegible: igual se recarga.
      }
      refresh();
    });
    const onVisible = () => {
      if (!document.hidden && pending.current) refresh();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      source.close();
      clearTimeout(timer.current);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [projectKey, router]);

  return <LiveContext.Provider value={versions}>{children}</LiveContext.Provider>;
}
