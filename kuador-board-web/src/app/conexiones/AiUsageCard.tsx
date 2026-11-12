"use client";

import { useState, useTransition } from "react";

import { setAiBudgetAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { AiUsageReport } from "@/lib/types";

const usd = (n: number) => new Intl.NumberFormat("es-EC", { style: "currency", currency: "USD", minimumFractionDigits: 2 }).format(n);
const num = (n: number) => new Intl.NumberFormat("es-EC").format(n);
const MONTH = (m: string) => new Intl.DateTimeFormat("es-EC", { month: "long", year: "numeric" }).format(new Date(`${m}-15T12:00:00Z`));

/** Consumo de IA del mes de los agentes que trabajan en el tablero, su costo estimado y el tope mensual. */
export function AiUsageCard({ usage: initial }: { usage: AiUsageReport }) {
  const [usage, setUsage] = useState(initial);
  const [budget, setBudget] = useState(initial.budgetUsd?.toString() ?? "");
  const [pending, start] = useTransition();
  const spent = usage.costUsd;
  const ratio = spent != null && usage.budgetUsd ? Math.min(1, spent / usage.budgetUsd) : null;

  return (
    <div className="flex flex-col gap-4 border-t border-line pt-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-[13px] font-medium text-fg-strong">Consumo de {MONTH(usage.month)}</h3>
        <span className="text-xs text-fg-muted">Solo agentes que trabajan en el tablero; no incluye el tiempo de ejecución de código.</span>
      </div>
      <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Stat label="Costo estimado" value={spent != null ? usd(spent) : usage.runs === 0 ? usd(0) : "Sin precio"} />
        <Stat label="Trabajos" value={num(usage.runs)} />
        <Stat label="Tokens de entrada" value={num(usage.inputTokens)} />
        <Stat label="Tokens de salida" value={num(usage.outputTokens)} />
      </dl>
      {ratio != null && (
        <div className="flex flex-col gap-1">
          <div className="h-1.5 overflow-hidden rounded-full bg-line" role="progressbar" aria-valuenow={Math.round(ratio * 100)} aria-valuemin={0} aria-valuemax={100} aria-label="Consumo frente al tope">
            <div className={ratio >= 1 ? "h-full bg-danger" : ratio >= 0.8 ? "h-full bg-warn" : "h-full bg-sol"} style={{ width: `${ratio * 100}%` }} />
          </div>
          <p className="text-xs text-fg-muted">
            {usd(spent ?? 0)} de {usd(usage.budgetUsd ?? 0)}
            {ratio >= 1 ? " · tope alcanzado: los agentes no arrancan hasta el próximo mes o hasta que lo subas." : ""}
          </p>
        </div>
      )}
      {usage.unpricedModels.length > 0 && (
        <p className="text-xs text-fg-faint">No hay precio configurado para {usage.unpricedModels.join(", ")}: el costo no se puede estimar y el tope no aplica.</p>
      )}
      {usage.byAgent.length > 0 && (
        <ul className="divide-y divide-line rounded-md border border-line text-[13px]">
          {usage.byAgent.map((a) => (
            <li key={a.agentId} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2">
              <span className="min-w-0 flex-1 truncate font-medium text-fg-strong">{a.name}</span>
              <span className="text-xs text-fg-muted">{num(a.runs)} trabajo{a.runs === 1 ? "" : "s"}</span>
              <span className="tabular w-20 text-right text-fg">{a.costUsd != null ? usd(a.costUsd) : "—"}</span>
            </li>
          ))}
        </ul>
      )}
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          const value = budget.trim() === "" ? null : Number(budget.replace(",", "."));
          if (value != null && (!Number.isFinite(value) || value <= 0)) {
            notify.error("Escribe un monto mayor que cero, o déjalo vacío para no tener tope.");
            return;
          }
          start(async () => {
            const r = await setAiBudgetAction(value);
            if (!r.ok) {
              notify.error("No se pudo guardar", r.error);
              return;
            }
            setUsage(r.data);
            notify.ok(value == null ? "Sin tope mensual" : `Tope mensual: ${usd(value)}`);
          });
        }}
      >
        <div className="w-48">
          <Field label="Tope mensual (USD)" hint="Vacío = sin tope.">
            {(id) => <TextInput id={id} inputMode="decimal" value={budget} onChange={(e) => setBudget(e.target.value)} placeholder="20" />}
          </Field>
        </div>
        <Button type="submit" loading={pending}>Guardar tope</Button>
      </form>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-[12px] text-fg-muted">{label}</dt>
      <dd className="tabular mt-0.5 text-sm font-medium text-fg-strong">{value}</dd>
    </div>
  );
}
