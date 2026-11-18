"use client";

import { Target } from "lucide-react";
import { useState, useTransition } from "react";

import { saveProjectGoalAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { TextArea } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";

/**
 * El objetivo del tablero: para qué existe y qué le importa al negocio. Los agentes lo leen con cada tarjeta y deciden
 * con esa brújula (qué priorizar, qué tan completo hacerlo, qué dejar afuera).
 */
export function GoalForm({ projectKey, initial }: { projectKey: string; initial: string | null }) {
  const [goal, setGoal] = useState(initial ?? "");
  const [saved, setSaved] = useState(initial ?? "");
  const [pending, start] = useTransition();
  return (
    <form
      className="flex flex-col gap-3 rounded-lg border border-sol/40 bg-sol/5 p-5"
      onSubmit={(e) => {
        e.preventDefault();
        start(async () => {
          const r = await saveProjectGoalAction(projectKey, goal);
          if (r.ok) {
            setSaved(r.data.goal ?? "");
            notify.ok(r.data.goal ? "Objetivo guardado" : "Objetivo quitado", r.data.goal ? "Los agentes lo leen desde su próxima tarjeta." : undefined);
          } else notify.error("No se pudo guardar", r.error);
        });
      }}
    >
      <label htmlFor="board-goal" className="flex items-center gap-2 text-[13px] font-medium text-fg-strong">
        <Target className="size-4 text-sol-text" aria-hidden /> Objetivo del tablero
      </label>
      <p className="-mt-1 text-xs leading-relaxed text-fg-muted">
        Una o dos frases: para qué existe y qué es lo más importante para el negocio. Los agentes lo leen con cada tarjeta.
      </p>
      <TextArea
        id="board-goal"
        rows={3}
        maxLength={1000}
        value={goal}
        onChange={(e) => setGoal(e.target.value)}
        placeholder="Ej.: Que la clínica agende citas en línea antes de marzo; lo más importante es que sea simple para pacientes mayores."
      />
      <div className="flex items-center justify-between gap-3">
        <span className="tabular text-xs text-fg-faint">{goal.length}/1000</span>
        <Button type="submit" tone="primary" size="sm" loading={pending} disabled={goal.trim() === saved.trim()}>
          Guardar objetivo
        </Button>
      </div>
    </form>
  );
}
