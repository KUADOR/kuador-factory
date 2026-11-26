"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { setStagesAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { Project } from "@/lib/types";

/** Cuántas veces puede volver atrás una tarjeta antes de que el tablero se la deje a una persona. */
export function LoopBrake({ project }: { project: Project }) {
  const router = useRouter();
  const [maxRework, setMaxRework] = useState(project.maxRework);
  const [pending, start] = useTransition();
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-lg border border-line bg-surface px-5 py-4">
      <span className="text-[13px] text-fg-muted">Si una tarjeta vuelve atrás más de</span>
      <span className="w-16 shrink-0">
        <TextInput size="sm" type="number" min={1} max={20} value={maxRework} onChange={(e) => setMaxRework(Number(e.target.value) || 1)} aria-label="Vueltas atrás permitidas" className="tabular text-center" />
      </span>
      <span className="text-[13px] text-fg-muted">veces, se asigna a la persona responsable.</span>
      <Button
        size="sm"
        className="ml-auto"
        loading={pending}
        disabled={maxRework === project.maxRework}
        onClick={() =>
          start(async () => {
            const r = await setStagesAction(project.key, project.stages, maxRework);
            if (r.ok) {
              notify.ok("Límite guardado");
              router.refresh();
            } else notify.error("No se pudo guardar", r.error);
          })
        }
      >
        Guardar
      </Button>
    </div>
  );
}
