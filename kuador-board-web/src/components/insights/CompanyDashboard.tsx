import { ArrowDownRight, ArrowUpRight, Bot, Minus, User } from "lucide-react";
import Link from "next/link";
import { clsx } from "clsx";

import { Section } from "@/components/ui/Section";
import type { InsightsCompany, Kpi } from "@/lib/types";

import { FlowChart, WeeklyChart } from "./Charts";
import { hours, shortDate, usd } from "./format";
import { HoursPerPoint } from "./HoursPerPoint";

/**
 * Un número grande con su comparación: la flecha dice si mejoró o empeoró (con texto, no solo color). {@code better}:
 * si es bueno que suba (entregas, ahorro) o que baje (tiempo de entrega, costo).
 */
function KpiTile({ label, kpi, format, better, hint }: { label: string; kpi: Kpi; format: (n: number | null) => string; better: "up" | "down"; hint?: React.ReactNode }) {
  const { value, previous } = kpi;
  let trend: { icon: typeof Minus; text: string; good: boolean | null } | null = null;
  if (value != null && previous != null && previous > 0 && !(hint && value === 0)) {
    const change = Math.round(((value - previous) / previous) * 100);
    if (change === 0) trend = { icon: Minus, text: "igual que antes", good: null };
    else trend = { icon: change > 0 ? ArrowUpRight : ArrowDownRight, text: `${change > 0 ? "+" : ""}${change} % vs. período anterior`, good: (change > 0) === (better === "up") };
  }
  return (
    <div className="flex min-w-0 flex-col gap-1 rounded-lg border border-line bg-surface px-4 py-3">
      <span className="text-xs text-fg-muted">{label}</span>
      <span className="tabular text-2xl font-semibold tracking-tight text-fg-strong">{format(value)}</span>
      {trend ? (
        <span className={clsx("flex items-center gap-1 text-[11px]", trend.good == null ? "text-fg-faint" : trend.good ? "text-ok" : "text-danger")}>
          <trend.icon className="size-3" aria-hidden /> {trend.text}
        </span>
      ) : (
        <span className="text-[11px] text-fg-faint">{hint ?? "sin período anterior para comparar"}</span>
      )}
    </div>
  );
}

/** «Cómo va la empresa»: lo que entregan, cuánto tardan, cuánto se ahorra, dónde se atasca y lo último que se terminó. */
export function CompanyDashboard({ data, canEditSettings }: { data: InsightsCompany; canEditSettings: boolean }) {
  const s = data.savings;
  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
        <KpiTile label="Tarjetas terminadas" kpi={data.done} format={(n) => (n == null ? "—" : String(n))} better="up" />
        <KpiTile label="Tiempo de entrega promedio" kpi={data.leadTimeHours} format={hours} better="down" />
        <KpiTile label="Horas ahorradas" kpi={data.hoursSaved} format={(n) => (n == null ? "—" : `${Math.round(n)} h`)} better="up" />
        <KpiTile
          label="Costo de IA por tarjeta"
          kpi={data.costPerCard}
          format={usd}
          better="down"
          hint={
            data.unpricedModels.length > 0
              ? "falta el precio de algún modelo"
              : data.costPerCard.value == null
                ? "sin entregas o sin gasto"
                : data.costPerCard.value === 0
                  ? "los agentes trabajaron con suscripciones (runner)"
                  : undefined
          }
        />
      </div>

      {/* Cómo se calcula el ahorro, siempre a la vista: es la cifra que tiene que ser creíble. */}
      <p className="-mt-2 text-xs leading-relaxed text-fg-muted">
        {s.cards > 0 ? (
          <>
            Ahorro calculado con <HoursPerPoint value={s.hoursPerPoint} canEdit={canEditSettings} /> sobre {s.cards} tarjeta{s.cards === 1 ? "" : "s"} aprobada
            {s.cards === 1 ? "" : "s"} con agentes: {s.points} puntos ≈ {hours(s.estimatedHours)} de una persona; los agentes tardaron {hours(s.agentHours)}.
          </>
        ) : (
          <>
            El ahorro se mide con las tarjetas que terminan con agentes y tienen puntos, a <HoursPerPoint value={s.hoursPerPoint} canEdit={canEditSettings} />.
            Todavía no hay ninguna en este período.
          </>
        )}
        {s.unestimated > 0 && ` ${s.unestimated} terminada${s.unestimated === 1 ? "" : "s"} sin puntos no cuenta${s.unestimated === 1 ? "" : "n"}: estímala${s.unestimated === 1 ? "" : "s"} para medirla${s.unestimated === 1 ? "" : "s"}.`}
      </p>

      <Section title="¿Dónde se atasca el trabajo?" description={`Últimos ${data.days} días: tiempo de cada columna con un agente, con una persona o esperando.`}>
        <FlowChart flow={data.flow} />
      </Section>

      <div className="grid gap-5 lg:grid-cols-2">
        <Section title="Entregas por semana" description="Las últimas 8 semanas.">
          <WeeklyChart weeks={data.weeks} />
        </Section>

        <Section title="Tus tableros" flush>
          <ul className="divide-y divide-line">
            {data.boards.map((b) => (
              <li key={b.key}>
                <Link href={`/projects/${b.key}`} className="flex flex-col gap-1 px-5 py-3 hover:bg-hover">
                  <span className="flex items-center gap-2">
                    <span className="font-mono text-xs text-fg-faint">{b.key}</span>
                    <span className="truncate text-[13px] font-medium text-fg-strong">{b.name}</span>
                    <span className="tabular ml-auto text-xs text-fg-muted">{b.done} terminadas</span>
                  </span>
                  <span className="tabular flex flex-wrap gap-x-3 text-xs text-fg-muted">
                    <span>{b.inProgress} en curso</span>
                    <span className={b.waitingDecision > 0 ? "text-fg" : undefined}>{b.waitingDecision} esperan decisión</span>
                    <span className={b.stuck > 0 ? "text-danger" : undefined}>{b.stuck} trabadas</span>
                    <span>{Math.round(b.reworkRate * 100)} % volvió atrás</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </Section>
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        <Section title="Últimas tareas terminadas" flush>
          {data.recent.length === 0 ? (
            <p className="px-5 py-4 text-[13px] text-fg-muted">Nada terminado en estos días.</p>
          ) : (
            <ul className="divide-y divide-line">
              {data.recent.map((r) => (
                <li key={r.key}>
                  <Link href={`/projects/${r.projectKey}?issue=${r.key}`} className="flex items-start gap-3 px-5 py-2.5 hover:bg-hover">
                    {r.agentsHelped ? <Bot className="mt-0.5 size-4 shrink-0 text-viz-agent" aria-label="Con agentes" /> : <User className="mt-0.5 size-4 shrink-0 text-fg-faint" aria-label="Solo personas" />}
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] text-fg-strong">{r.title}</span>
                      <span className="block text-xs text-fg-muted">
                        <span className="font-mono">{r.key}</span> · {r.projectName} · {shortDate(r.doneAt)} · en {hours(r.leadTimeHours)}
                        {r.by && ` · cerró ${r.by}`}
                      </span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Section>

        <Section title="Agentes" description={data.costUsd != null ? `Gasto de IA del período: ${usd(data.costUsd)}.` : undefined} flush>
          {data.agents.length === 0 ? (
            <p className="px-5 py-4 text-[13px] text-fg-muted">Los agentes todavía no trabajaron en este período.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="text-fg-faint">
                  <tr>
                    <th className="px-5 py-2 font-medium">Agente</th>
                    <th className="px-2 py-2 text-right font-medium">Entregas</th>
                    <th className="px-2 py-2 text-right font-medium">Falló</th>
                    <th className="px-2 py-2 text-right font-medium">Tarda</th>
                    <th className="px-5 py-2 text-right font-medium">Costo</th>
                  </tr>
                </thead>
                <tbody className="tabular text-fg">
                  {data.agents.map((a) => (
                    <tr key={a.id} className="border-t border-line">
                      <td className="px-5 py-2 text-fg-strong">{a.name}</td>
                      <td className="px-2 py-2 text-right">{a.delivered}</td>
                      <td className={clsx("px-2 py-2 text-right", a.failed > 0 && "text-danger")}>{a.failed}</td>
                      <td className="px-2 py-2 text-right">{a.delivered ? hours(a.avgMinutes / 60) : "—"}</td>
                      <td className="px-5 py-2 text-right">{a.costUsd == null ? "—" : usd(a.costUsd)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data.unpricedModels.length > 0 && (
            <p className="border-t border-line px-5 py-2 text-xs text-fg-muted">Sin precio cargado: {data.unpricedModels.join(", ")} (Plataforma → IA).</p>
          )}
        </Section>
      </div>
    </div>
  );
}
