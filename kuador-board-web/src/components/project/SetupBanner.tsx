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
    <div className="mx-6 mt-4 flex items-center gap-3 rounded-md border border-line bg-surface px-4 py-2.5 text-[13px]">
      <span className="text-fg">
        Faltan {pending} paso{pending === 1 ? "" : "s"} para dejar el proyecto listo.
      </span>
      <Link href={`/projects/${projectKey}/setup`} className="k-link">
        Terminar de configurar
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
