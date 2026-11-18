import { Bot, Inbox, LoaderCircle } from "lucide-react";
import Link from "next/link";

import { Section } from "@/components/ui/Section";
import type { InsightsMine, RunnerMachine } from "@/lib/types";

import { ago, hours } from "./format";

/** «Mi trabajo»: lo que espera de mí, lo que hacen mis agentes, mis épicas y mi semana. */
export function MyWork({ data, decisions, machines }: { data: InsightsMine; decisions: number; machines: RunnerMachine[] }) {
  const w = data.week;
  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
        <Link href="/decisions" className="flex flex-col gap-1 rounded-lg border border-line bg-surface px-4 py-3 hover:bg-hover">
          <span className="flex items-center gap-1.5 text-xs text-fg-muted">
            <Inbox className="size-3.5" aria-hidden /> Esperan tu decisión
          </span>
          <span className={`tabular text-2xl font-semibold ${decisions > 0 ? "text-sol-text" : "text-fg-strong"}`}>{decisions}</span>
        </Link>
        <Tile label="Cerraste esta semana" value={String(w.closed)} />
        <Tile label="Tu tiempo de entrega" value={hours(w.leadTimeHours)} />
        <Tile label="Entregas de tus agentes" value={String(w.agentDeliveries)} hint="esta semana" />
      </div>

      <Section title="Asignado a ti" flush>
        {data.assigned.length === 0 ? (
          <p className="px-5 py-4 text-[13px] text-fg-muted">No tienes tarjetas asignadas.</p>
        ) : (
          <ul className="divide-y divide-line">
            {data.assigned.map((c) => (
              <li key={c.key}>
                <Link href={`/projects/${c.projectKey}?issue=${c.key}`} className="flex flex-col px-5 py-2.5 hover:bg-hover">
                  <span className="truncate text-[13px] text-fg-strong">{c.title}</span>
                  <span className="text-xs text-fg-muted">
                    <span className="font-mono">{c.key}</span> · {c.projectName} · {c.column} · {ago(c.since)}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <div className="grid gap-5 lg:grid-cols-2">
        <Section title="Lo que hacen tus agentes" flush>
          {data.supervised.length === 0 && data.deliveredToday.length === 0 ? (
            <p className="px-5 py-4 text-[13px] text-fg-muted">Tus agentes no tienen trabajo ahora.</p>
          ) : (
            <ul className="divide-y divide-line">
              {data.supervised.map((c) => (
                <li key={c.key}>
                  <Link href={`/projects/${c.projectKey}?issue=${c.key}`} className="flex items-start gap-2.5 px-5 py-2.5 hover:bg-hover">
                    {c.working ? (
                      <LoaderCircle className="mt-0.5 size-4 shrink-0 animate-spin text-viz-agent [animation-duration:2.4s]" aria-label="Trabajando" />
                    ) : (
                      <Bot className="mt-0.5 size-4 shrink-0 text-fg-faint" aria-label="En cola" />
                    )}
                    <span className="min-w-0">
                      <span className="block truncate text-[13px] text-fg-strong">{c.title}</span>
                      <span className="block text-xs text-fg-muted">
                        {c.agent} · {c.working ? "trabajando" : "en cola"} · {c.column}
                      </span>
                    </span>
                  </Link>
                </li>
              ))}
              {data.deliveredToday.map((d) => (
                <li key={d.key + d.agent}>
                  <Link href={`/projects/${d.projectKey}?issue=${d.key}`} className="flex items-start gap-2.5 px-5 py-2.5 hover:bg-hover">
                    <span aria-hidden className="mt-0.5 w-4 shrink-0 text-center text-[13px]">✅</span>
                    <span className="min-w-0">
                      <span className="block truncate text-[13px] text-fg-strong">{d.title}</span>
                      <span className="block text-xs text-fg-muted">
                        {d.agent} entregó {ago(d.at)}
                      </span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Section>

        <Section title="Tus épicas" flush>
          {data.epics.length === 0 ? (
            <p className="px-5 py-4 text-[13px] text-fg-muted">No supervisas épicas abiertas.</p>
          ) : (
            <ul className="divide-y divide-line">
              {data.epics.map((e) => {
                const pct = e.total === 0 ? 0 : Math.round((e.done / e.total) * 100);
                return (
                  <li key={e.key}>
                    <Link href={`/projects/${e.projectKey}?issue=${e.key}`} className="flex flex-col gap-1.5 px-5 py-2.5 hover:bg-hover">
                      <span className="flex items-center gap-2">
                        <span className="min-w-0 flex-1 truncate text-[13px] text-fg-strong">{e.title}</span>
                        <span className="tabular text-xs text-fg-muted">
                          {e.done}/{e.total}
                        </span>
                      </span>
                      <span className="h-1.5 overflow-hidden rounded-full bg-sunken" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label={`${e.title}: ${pct} %`}>
                        <span className="block h-full rounded-full bg-ok" style={{ width: `${pct}%` }} />
                      </span>
                      <span className="text-xs text-fg-muted">
                        {e.total === 0 ? "Todavía sin tareas" : `${pct} % terminado · ${e.inProgress} en curso`}
                      </span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          )}
        </Section>
      </div>

      {machines.length > 0 && (
        <Section title="Tus máquinas" flush>
          <ul className="divide-y divide-line">
            {machines.map((m) => (
              <li key={m.id} className="flex items-center gap-2 px-5 py-2.5 text-xs text-fg-muted">
                <span aria-hidden className={`size-2 shrink-0 rounded-full ${!m.online ? "bg-fg-faint" : m.paused ? "bg-warn" : "bg-ok"}`} />
                <span className="truncate text-[13px] text-fg">{m.name}</span>
                <span className="ml-auto shrink-0">
                  {!m.online ? `desconectada ${ago(m.lastSeen)}` : m.paused ? "en pausa" : m.working.length ? `trabajando ${m.working.join(", ")}` : "esperando trabajo"}
                </span>
              </li>
            ))}
          </ul>
        </Section>
      )}
    </div>
  );
}

function Tile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-line bg-surface px-4 py-3">
      <span className="text-xs text-fg-muted">{label}</span>
      <span className="tabular text-2xl font-semibold text-fg-strong">{value}</span>
      {hint && <span className="text-[11px] text-fg-faint">{hint}</span>}
    </div>
  );
}
