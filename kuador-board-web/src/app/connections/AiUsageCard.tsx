"use client";

import { useState, useTransition } from "react";

import { setAgentBudgetAction, setAiBudgetAction, setCardCapAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { AiUsageReport } from "@/lib/types";

const usd = (n: number) => new Intl.NumberFormat("es-EC", { style: "currency", currency: "USD", minimumFractionDigits: 2 }).format(n);
const num = (n: number) => new Intl.NumberFormat("es-EC").format(n);
const MONTH = (m: string) => new Intl.DateTimeFormat("es-EC", { month: "long", year: "numeric" }).format(new Date(`${m}-15T12:00:00Z`));

/** Monto escrito por la persona: vacío = sin tope; un número mayor que cero; si no, null y avisa. */
function parseUsd(text: string): number | null | undefined {
  if (text.trim() === "") return null;
  const v = Number(text.replace(",", "."));
  if (!Number.isFinite(v) || v <= 0) {
    notify.error("Escribe un monto mayor que cero, o déjalo vacío para no tener tope.");
    return undefined;
  }
  return v;
}

/**
 * Consumo de IA del mes de los agentes que trabajan en el tablero, su costo estimado y sus frenos: el tope del espacio,
 * el de cada agente y el de cada tarjeta. Al 80 % y al llegar al tope se avisa a quien administra.
 */
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
      {usage.cachedTokens > 0 && usage.inputTokens > 0 && (
        <p className="text-xs text-fg-muted">
          {Math.round((usage.cachedTokens * 100) / usage.inputTokens)}% de la entrada salió del caché de prompts, que cuesta cerca del 10%.
        </p>
      )}
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
        <p className="text-xs text-warn">
          Falta el precio de {usage.unpricedModels.join(", ")}: el gasto no se puede calcular y, con un tope, los agentes del tablero se frenan por
          precaución. Cárgalo en Plataforma → IA.
        </p>
      )}
      {usage.byAgent.length > 0 && (
        <ul className="divide-y divide-line rounded-md border border-line text-[13px]">
          {usage.byAgent.map((a) => (
            <li key={a.agentId} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2">
              <span className="min-w-0 flex-1 truncate font-medium text-fg-strong">{a.name}</span>
              <span className="text-xs text-fg-muted">{num(a.runs)} trabajo{a.runs === 1 ? "" : "s"}</span>
              <span className="tabular w-20 text-right text-fg">{a.costUsd != null ? usd(a.costUsd) : "—"}</span>
              <AgentCap agentId={a.agentId} name={a.name} value={a.budgetUsd} onSaved={setUsage} />
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
      <CardCap value={usage.cardCapUsd} onSaved={setUsage} />
      <p className="text-xs text-fg-faint">
        Te avisamos al 80 % y al llegar a cada tope (correo, Telegram o el celular). Llegado un tope, esos agentes no toman trabajo nuevo hasta que lo
        subas o empiece el mes.
      </p>
    </div>
  );
}

/** Tope por tarjeta: corta los bucles (rehacer, que la devuelvan, rehacer) antes de que gasten el mes. */
function CardCap({ value, onSaved }: { value: number | null; onSaved: (r: AiUsageReport) => void }) {
  const [text, setText] = useState(value?.toString() ?? "");
  const [pending, start] = useTransition();
  return (
    <form
      className="flex flex-wrap items-end gap-3"
      onSubmit={(e) => {
        e.preventDefault();
        const v = parseUsd(text);
        if (v === undefined) return;
        start(async () => {
          const r = await setCardCapAction(v);
          if (!r.ok) {
            notify.error("No se pudo guardar", r.error);
            return;
          }
          onSaved(r.data);
          notify.ok(v == null ? "Sin tope por tarjeta" : `Ninguna tarjeta gasta más de ${usd(v)}`);
        });
      }}
    >
      <div className="w-48">
        <Field label="Tope por tarjeta (USD)" hint="Ninguna tarjeta gasta más que esto.">
          {(id) => <TextInput id={id} inputMode="decimal" value={text} onChange={(e) => setText(e.target.value)} placeholder="3" />}
        </Field>
      </div>
      <Button type="submit" loading={pending}>
        Guardar
      </Button>
    </form>
  );
}

/** El tope del mes de un agente, editable en su fila. */
function AgentCap({ agentId, name, value, onSaved }: { agentId: string; name: string; value: number | null; onSaved: (r: AiUsageReport) => void }) {
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(value?.toString() ?? "");
  const [pending, start] = useTransition();
  if (!editing) {
    return (
      <button type="button" onClick={() => setEditing(true)} className="text-xs text-fg-faint hover:text-fg-strong" aria-label={`Tope de ${name}`}>
        {value != null ? `tope ${usd(value)}` : "sin tope"}
      </button>
    );
  }
  return (
    <form
      className="flex items-center gap-1.5"
      onSubmit={(e) => {
        e.preventDefault();
        const v = parseUsd(text);
        if (v === undefined) return;
        start(async () => {
          const r = await setAgentBudgetAction(agentId, v);
          if (!r.ok) {
            notify.error("No se pudo guardar", r.error);
            return;
          }
          onSaved(r.data);
          setEditing(false);
          notify.ok(v == null ? `${name} sin tope propio` : `Tope de ${name}: ${usd(v)} al mes`);
        });
      }}
    >
      <input
        value={text}
        onChange={(e) => setText(e.target.value)}
        inputMode="decimal"
        autoFocus
        placeholder="vacío = sin tope"
        aria-label={`Tope mensual de ${name} (USD)`}
        className="h-7 w-24 rounded-md border border-line bg-surface px-2 text-xs text-fg-strong"
      />
      <Button size="sm" type="submit" loading={pending}>
        OK
      </Button>
    </form>
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
