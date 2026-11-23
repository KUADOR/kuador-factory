"use client";

import { X } from "lucide-react";
import Link from "next/link";
import { useSyncExternalStore } from "react";

import { Button } from "@/components/ui/Button";

const key = (projectKey: string) => `kf:onboarding-dismissed:${projectKey}`;
const listeners = new Set<() => void>();

function dismissed(projectKey: string): boolean {
  try {
    return localStorage.getItem(key(projectKey)) === "1";
  } catch {
    return false;
  }
}

/** Aviso en el tablero mientras falten pasos del asistente de inicio. Se puede cerrar (se recuerda en este navegador). */
export function SetupBanner({ projectKey, pending }: { projectKey: string; pending: number }) {
  const hidden = useSyncExternalStore(
    (cb) => {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
    () => dismissed(projectKey),
    () => true,
  );
  if (hidden || pending === 0) return null;
  return (
    <div className="mx-4 mt-3 flex items-center gap-3 rounded-md border border-line bg-surface py-1.5 pr-1.5 pl-3 text-[13px] sm:mx-6 sm:mt-4 sm:py-2.5 sm:pl-4">
      <span className="min-w-0 truncate text-fg">
        Faltan {pending} paso{pending === 1 ? "" : "s"}<span className="hidden sm:inline"> para dejar el tablero listo</span>.
      </span>
      <Link href={`/projects/${projectKey}/setup`} className="k-link shrink-0">
        <span className="sm:hidden">Terminar</span>
        <span className="hidden sm:inline">Terminar de configurar</span>
      </Link>
      <span className="ml-auto">
        <Button
          size="sm"
          tone="ghost"
          icon={X}
          iconOnly
          label="Ocultar aviso"
          onClick={() => {
            try {
              localStorage.setItem(key(projectKey), "1");
            } catch {
              // Sin almacenamiento: el aviso vuelve a aparecer en la próxima visita.
            }
            listeners.forEach((l) => l());
          }}
        />
      </span>
    </div>
  );
}
