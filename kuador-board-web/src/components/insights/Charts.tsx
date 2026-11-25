"use client";

import { Lock } from "lucide-react";
import { useState } from "react";
import { clsx } from "clsx";

import type { InsightsCompany } from "@/lib/types";

import { hours, shortDate } from "./format";

/** Las tres maneras de pasar el tiempo, siempre con el mismo color (sigue a la entidad, no al orden). */
const KIND = {
  agent: { label: "Agente trabajando", swatch: "bg-viz-agent" },
  person: { label: "Con una persona", swatch: "bg-viz-person" },
  wait: { label: "Esperando", swatch: "bg-viz-wait" },
} as const;

function Legend({ items }: { items: { swatch: string; label: string }[] }) {
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-fg-muted" aria-label="Leyenda">
      {items.map((i) => (
        <li key={i.label} className="flex items-center gap-1.5">
          <span aria-hidden className={clsx("size-2.5 rounded-sm", i.swatch)} />
          {i.label}
        </li>
      ))}
    </ul>
  );
}

function DataTable({ head, rows }: { head: string[]; rows: (string | number)[][] }) {
  return (
    <details className="text-xs">
      <summary className="cursor-pointer text-fg-faint hover:text-fg-muted">Ver los datos</summary>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full text-left">
          <thead className="text-fg-faint">
            <tr>{head.map((h) => <th key={h} className="py-1 pr-4 font-medium">{h}</th>)}</tr>
          </thead>
          <tbody className="tabular text-fg">
            {rows.map((r, i) => (
              <tr key={i} className="border-t border-line">
                {r.map((c, j) => <td key={j} className="py-1 pr-4">{c}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}

/**
 * ¿Dónde se atasca el trabajo? Por columna, el tiempo con un agente trabajando, con una persona y esperando (sin nadie o
 * en una puerta). Una barra horizontal apilada por columna, todas en la misma escala.
 */
export function FlowChart({ flow }: { flow: InsightsCompany["flow"] }) {
  const [board, setBoard] = useState(flow[0]?.projectKey ?? "");
  const [hover, setHover] = useState<string | null>(null);
  const picked = flow.find((f) => f.projectKey === board) ?? flow[0];
  // Las columnas por las que la tarjeta solo pasó (0 h) no aportan: se ocultan.
  const current = picked && { ...picked, stages: picked.stages.filter((s) => s.agentHours + s.personHours + s.waitingHours > 0) };
  if (!current || current.stages.length === 0) return <p className="text-[13px] text-fg-muted">Todavía no hay movimiento suficiente para medir dónde se va el tiempo.</p>;
  const total = current.agentHours + current.personHours + current.waitingHours;
  const waitShare = total > 0 ? Math.round((current.waitingHours / total) * 100) : 0;
  const max = Math.max(...current.stages.map((s) => s.agentHours + s.personHours + s.waitingHours), 0.1);
  const worst = [...current.stages].sort((a, b) => b.waitingHours - a.waitingHours)[0];

  return (
    <div className="flex flex-col gap-3">
      {flow.length > 1 && (
        <div className="flex gap-1.5 overflow-x-auto" role="tablist" aria-label="Tablero">
          {flow.map((f) => (
            <button
              key={f.projectKey}
              type="button"
              role="tab"
              aria-selected={f.projectKey === current.projectKey}
              onClick={() => setBoard(f.projectKey)}
              className={clsx(
                "h-8 shrink-0 rounded-full border px-3 text-[13px] whitespace-nowrap",
                f.projectKey === current.projectKey ? "border-sol/60 bg-sol/15 font-medium text-fg-strong" : "border-line text-fg-muted",
              )}
            >
              {f.name}
            </button>
          ))}
        </div>
      )}
      {/* El titular: lo que más importa, en palabras. */}
      <p className="text-[13px] text-fg">
        <span className="font-semibold text-fg-strong">{waitShare} %</span> del tiempo las tarjetas estuvieron esperando
        {worst && worst.waitingHours > 0 && (
          <>
            ; donde más, en <span className="font-medium text-fg-strong">«{worst.name}»</span> ({hours(worst.waitingHours)})
          </>
        )}
        .
      </p>
      <Legend items={[KIND.agent, KIND.person, KIND.wait].map((k) => ({ swatch: k.swatch, label: k.label }))} />
      <ul className="flex flex-col gap-2.5">
        {current.stages.map((s) => {
          const parts = [
            { id: "agent", v: s.agentHours, cls: "bg-viz-agent", label: KIND.agent.label },
            { id: "person", v: s.personHours, cls: "bg-viz-person", label: KIND.person.label },
            { id: "wait", v: s.waitingHours, cls: "bg-viz-wait", label: KIND.wait.label },
          ].filter((p) => p.v > 0);
          const rowTotal = s.agentHours + s.personHours + s.waitingHours;
          return (
            <li key={s.column} className="grid grid-cols-[minmax(0,7.5rem)_minmax(0,1fr)_3.5rem] items-center gap-2 sm:grid-cols-[10rem_minmax(0,1fr)_4rem]">
              <span className="flex min-w-0 items-center gap-1 truncate text-xs text-fg" title={s.name}>
                {s.gate && <Lock className="size-3 shrink-0 text-fg-faint" aria-label="Paso de aprobación" />}
                <span className="truncate">{s.name}</span>
              </span>
              <div className="relative h-5" onMouseLeave={() => setHover(null)}>
                <div className="flex h-full gap-[2px]" style={{ width: `${Math.max(2, (rowTotal / max) * 100)}%` }}>
                  {parts.map((p, i) => (
                    <button
                      key={p.id}
                      type="button"
                      aria-label={`${s.name}: ${p.label} ${hours(p.v)}`}
                      onMouseEnter={() => setHover(`${s.column}:${p.id}`)}
                      onFocus={() => setHover(`${s.column}:${p.id}`)}
                      onClick={() => setHover(`${s.column}:${p.id}`)}
                      className={clsx("h-full min-w-[3px]", p.cls, i === parts.length - 1 && "rounded-r", i === 0 && "rounded-l-sm")}
                      style={{ flexGrow: p.v, flexBasis: 0 }}
                    />
                  ))}
                </div>
                {parts.map(
                  (p) =>
                    hover === `${s.column}:${p.id}` && (
                      <div key={p.id} role="tooltip" className="pointer-events-none absolute -top-8 left-0 z-10 rounded-md border border-line bg-surface px-2 py-1 text-xs whitespace-nowrap text-fg shadow-float">
                        <span className="font-medium text-fg-strong">{p.label}</span> · {hours(p.v)}
                      </div>
                    ),
                )}
              </div>
              <span className="tabular text-right text-xs text-fg-muted">{hours(rowTotal)}</span>
            </li>
          );
        })}
      </ul>
      <DataTable
        head={["Paso", "Agente", "Persona", "Esperando"]}
        rows={current.stages.map((s) => [s.name, hours(s.agentHours), hours(s.personHours), hours(s.waitingHours)])}
      />
    </div>
  );
}

/** Entregas por semana (las últimas 8), con agentes y sin ellos, apiladas sobre la misma base. */
export function WeeklyChart({ weeks }: { weeks: InsightsCompany["weeks"] }) {
  const [hover, setHover] = useState<number | null>(null);
  const max = Math.max(...weeks.map((w) => w.withAgents + w.withoutAgents), 1);
  const top = Math.max(1, Math.ceil(max / 2) * 2);
  return (
    <div className="flex flex-col gap-3">
      <Legend
        items={[
          { swatch: "bg-viz-agent", label: "Con agentes" },
          { swatch: "bg-viz-person", label: "Solo personas" },
        ]}
      />
      <div className="relative flex h-36 items-end gap-2 border-b border-line pt-4" onMouseLeave={() => setHover(null)}>
        {/* Una sola línea de guía a la mitad: suficiente para leer la escala sin ensuciar. */}
        <span aria-hidden className="pointer-events-none absolute inset-x-0 top-1/2 border-t border-dashed border-line" />
        <span className="tabular pointer-events-none absolute top-1/2 -translate-y-full text-[10px] text-fg-faint">{top / 2}</span>
        {weeks.map((w, i) => {
          const total = w.withAgents + w.withoutAgents;
          return (
            <button
              key={w.start}
              type="button"
              aria-label={`Semana del ${shortDate(w.start)}: ${w.withAgents} con agentes, ${w.withoutAgents} solo personas`}
              onMouseEnter={() => setHover(i)}
              onFocus={() => setHover(i)}
              onClick={() => setHover(i)}
              className="relative flex h-full flex-1 flex-col-reverse items-stretch gap-[2px]"
            >
              {w.withAgents > 0 && <span className={clsx("bg-viz-agent", w.withoutAgents === 0 && "rounded-t")} style={{ height: `${(w.withAgents / top) * 100}%` }} />}
              {w.withoutAgents > 0 && <span className="rounded-t bg-viz-person" style={{ height: `${(w.withoutAgents / top) * 100}%` }} />}
              {hover === i && (
                <span role="tooltip" className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-1 -translate-x-1/2 rounded-md border border-line bg-surface px-2 py-1 text-left text-xs whitespace-nowrap text-fg shadow-float">
                  <span className="block font-medium text-fg-strong">Semana del {shortDate(w.start)}</span>
                  {total} entregadas · {w.withAgents} con agentes
                </span>
              )}
            </button>
          );
        })}
      </div>
      <div className="flex gap-2">
        {weeks.map((w, i) => (
          <span key={w.start} className={clsx("tabular flex-1 text-center text-[10px] text-fg-faint", i % 2 === 1 && "max-sm:invisible")}>
            {shortDate(w.start)}
          </span>
        ))}
      </div>
      <DataTable head={["Semana", "Con agentes", "Solo personas"]} rows={weeks.map((w) => [shortDate(w.start), w.withAgents, w.withoutAgents])} />
    </div>
  );
}
