"use client";

import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";

import { decisionsCountAction } from "@/app/actions";

/** Avísale al menú que una decisión cambió (aprobar, devolver, fusionar, responder): vuelve a contar enseguida. */
export const DECISIONS_CHANGED = "kuador:decisions-changed";

export function decisionsChanged() {
  window.dispatchEvent(new Event(DECISIONS_CHANGED));
}

/**
 * El número de «Mis decisiones» al día: el que trae la página y, después, se vuelve a pedir al cambiar de página, al
 * volver a la app (en el celular queda abierta horas), cada minuto y cuando una acción avisa que cambió.
 */
export function useDecisionsCount(initial: number): number {
  const path = usePathname();
  const [count, setCount] = useState(initial);
  const [seen, setSeen] = useState(initial);
  if (initial !== seen) {
    setSeen(initial);
    setCount(initial);
  }
  useEffect(() => {
    let alive = true;
    const refresh = () => {
      if (document.hidden) return;
      void decisionsCountAction().then((r) => alive && r.ok && setCount(r.data));
    };
    const timer = setInterval(refresh, 60_000);
    window.addEventListener(DECISIONS_CHANGED, refresh);
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      alive = false;
      clearInterval(timer);
      window.removeEventListener(DECISIONS_CHANGED, refresh);
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [path]);
  return count;
}
