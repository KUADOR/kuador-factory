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
    // En el celular la app se suspende y el navegador puede dejar la conexión cerrada para siempre (o rendirse tras
    // un error del servidor): se reabre a mano, con espera creciente, y al volver a la app.
    let source: EventSource | null = null;
    let connected = false;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let attempts = 0;
    let disposed = false;

    const open = () => {
      if (disposed) return;
      clearTimeout(retry);
      source?.close();
      const es = new EventSource(`/live?project=${encodeURIComponent(projectKey)}`);
      source = es;
      es.addEventListener("listo", () => {
        attempts = 0;
        // Al reconectar pudo perderse algo mientras estaba cortado: se recarga por las dudas.
        if (connected) refresh();
        connected = true;
      });
      es.addEventListener("cambio", (e) => {
        try {
          const change = JSON.parse((e as MessageEvent<string>).data) as Change;
          if (change.issue) setVersions((v) => ({ ...v, [change.issue!]: (v[change.issue!] ?? 0) + 1 }));
        } catch {
          // Aviso ilegible: igual se recarga.
        }
        refresh();
      });
      es.onerror = () => {
        // CONNECTING: el navegador ya reintenta solo. CLOSED: se rindió; reintentamos nosotros.
        if (es.readyState !== EventSource.CLOSED || document.hidden) return;
        attempts += 1;
        retry = setTimeout(open, Math.min(30_000, 1000 * 2 ** Math.min(attempts, 5)));
      };
    };

    const onVisible = () => {
      if (document.hidden) return;
      if (!source || source.readyState === EventSource.CLOSED) open();
      if (pending.current) refresh();
    };
    const onOnline = () => {
      if (!source || source.readyState !== EventSource.OPEN) open();
    };

    open();
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("online", onOnline);
    window.addEventListener("pageshow", onVisible);
    return () => {
      disposed = true;
      source?.close();
      clearTimeout(retry);
      clearTimeout(timer.current);
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("online", onOnline);
      window.removeEventListener("pageshow", onVisible);
    };
  }, [projectKey, router]);

  return <LiveContext.Provider value={versions}>{children}</LiveContext.Provider>;
}
