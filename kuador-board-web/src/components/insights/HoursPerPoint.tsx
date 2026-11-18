"use client";

import { Pencil } from "lucide-react";
import { useState, useTransition } from "react";

import { saveHoursPerPointAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { notify } from "@/components/ui/Toaster";

/** «Calculado con 4 h por punto»: quien administra lo ajusta a su realidad y el ahorro se recalcula. */
export function HoursPerPoint({ value, canEdit }: { value: number; canEdit: boolean }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(String(value));
  const [pending, start] = useTransition();
  if (!editing) {
    return (
      <span className="inline-flex items-center gap-1">
        {value.toLocaleString("es")} h por punto
        {canEdit && (
          <button type="button" onClick={() => setEditing(true)} aria-label="Cambiar horas por punto" className="rounded p-0.5 text-fg-faint hover:text-fg-strong">
            <Pencil className="size-3" aria-hidden />
          </button>
        )}
      </span>
    );
  }
  return (
    <form
      className="inline-flex items-center gap-1.5"
      onSubmit={(e) => {
        e.preventDefault();
        const n = Number(draft.replace(",", "."));
        start(async () => {
          const r = await saveHoursPerPointAction(n);
          if (r.ok) {
            notify.ok("Listo", `Ahora el ahorro se calcula con ${r.data.hoursPerPoint} h por punto.`);
            setEditing(false);
          } else notify.error("No se pudo guardar", r.error);
        });
      }}
    >
      <input
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        inputMode="decimal"
        aria-label="Horas por punto"
        autoFocus
        className="h-7 w-14 rounded-md border border-line bg-surface px-2 text-xs text-fg-strong"
      />
      <span>h por punto</span>
      <Button size="sm" tone="primary" type="submit" loading={pending}>
        Guardar
      </Button>
    </form>
  );
}
