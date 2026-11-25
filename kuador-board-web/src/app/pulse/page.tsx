import type { Metadata } from "next";
import Link from "next/link";

import { PageHeader } from "@/components/shell/PageHeader";
import { canEdit } from "@/lib/access";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import type { AgentRun, Member } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Pulso del día" };

const DAY = 24 * 60 * 60 * 1000;

const ago = (iso: string | null, now: number) => {
  if (!iso) return "nunca";
  const min = Math.max(0, Math.round((now - new Date(iso).getTime()) / 60000));
  if (min < 1) return "ahora";
  if (min < 60) return `hace ${min} min`;
  const h = Math.round(min / 60);
  return h < 24 ? `hace ${h} h` : `hace ${Math.round(h / 24)} d`;
};
const usd = (n: number) => `USD ${n.toFixed(2)}`;

const DOT: Record<AgentRun["status"], string> = { SUCCEEDED: "bg-ok", FAILED: "bg-danger", RUNNING: "bg-sol", SKIPPED: "bg-fg-faint" };

/**
 * Pulso del día: en 20 segundos, qué hicieron los agentes en las últimas 24 horas, qué te espera, si tu PC (el runner)
 * está trabajando y cuánto se gastó.
 */
export default async function PulsePage() {
  let data;
  try {
    data = await Promise.all([
      boardApi.agentRuns({ limit: 100 }),
      boardApi.decisions(),
      boardApi.members(),
      boardApi.runnerTokens().catch(() => []),
      boardApi.runnerMachines().catch(() => []),
      // El gasto lo ve quien administra; para el resto la API responde 403.
      boardApi.aiUsage().catch(() => null),
      boardApi.me().catch(() => null),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [runs, decisions, members, runners, machines, usage, me] = data;
  const canInstall = me != null && canEdit(me);
  // eslint-disable-next-line react-hooks/purity -- página dinámica: «ahora» es el momento de la petición
  const now = Date.now();
  const recent = runs.filter((r) => now - new Date(r.createdAt).getTime() < DAY);
  const count = (s: AgentRun["status"]) => recent.filter((r) => r.status === s).length;
  const today = recent.reduce((sum, r) => sum + (r.costUsd ?? 0), 0);
  const nameOf = (id: string) => members.find((m: Member) => m.id === id)?.name ?? "Agente";
  const lastRunner = runners.map((t) => t.lastUsedAt).filter((x): x is string => !!x).sort().at(-1) ?? null;
  const runnerOnline = lastRunner != null && now - new Date(lastRunner).getTime() < 15 * 60 * 1000;

  const tiles = [
    { n: decisions.length, label: "esperan tu decisión", href: "/decisions", tone: decisions.length > 0 ? "text-sol-text" : "text-fg-strong" },
    { n: count("SUCCEEDED"), label: "entregas de agentes", href: null, tone: "text-ok" },
    { n: count("RUNNING"), label: "trabajando ahora", href: null, tone: "text-fg-strong" },
    { n: count("FAILED"), label: "trabados", href: null, tone: count("FAILED") > 0 ? "text-danger" : "text-fg-strong" },
  ];

  return (
    <div className="mx-auto w-full max-w-2xl">
      <PageHeader title="Pulso del día" description="Las últimas 24 horas de tus tableros." />
      <div className="flex flex-col gap-5 px-4 py-5 sm:px-6">
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {tiles.map((t) => {
            const body = (
              <>
                <span className={`tabular text-2xl font-semibold ${t.tone}`}>{t.n}</span>
                <span className="text-xs text-fg-muted">{t.label}</span>
              </>
            );
            return t.href ? (
              <Link key={t.label} href={t.href} className="flex flex-col gap-0.5 rounded-lg border border-line bg-surface px-3.5 py-3 hover:bg-hover">
                {body}
              </Link>
            ) : (
              <div key={t.label} className="flex flex-col gap-0.5 rounded-lg border border-line bg-surface px-3.5 py-3">
                {body}
              </div>
            );
          })}
        </div>

        {machines.length > 0 ? (
          // Con runner: cada máquina con lo que está haciendo, en una línea.
          <Link href="/local-agents" className="flex flex-col gap-1.5 rounded-lg border border-line bg-surface px-4 py-3 hover:bg-hover" aria-label="Tus máquinas">
            <p className="text-[13px] font-medium text-fg-strong">Tus máquinas</p>
            <ul className="flex flex-col gap-1">
              {machines.map((m) => (
                <li key={m.id} className="flex items-center gap-2 text-xs text-fg-muted">
                  <span aria-hidden className={`size-2 shrink-0 rounded-full ${!m.online ? "bg-fg-faint" : m.paused ? "bg-warn" : "bg-ok"}`} />
                  <span className="truncate text-fg">{m.name}</span>
                  <span className="ml-auto shrink-0">
                    {!m.online ? `desconectada ${ago(m.lastSeen, now)}` : m.paused ? "en pausa" : m.working.length ? `trabajando ${m.working.join(", ")}` : "esperando trabajo"}
                  </span>
                </li>
              ))}
            </ul>
          </Link>
        ) : (
          <section className="flex flex-col gap-2 rounded-lg border border-line bg-surface px-4 py-3" aria-label="Tu PC">
            <p className="text-[13px] font-medium text-fg-strong">
              <span aria-hidden className={`mr-1.5 inline-block size-2 rounded-full ${runnerOnline ? "bg-ok" : "bg-fg-faint"}`} />
              Tu PC (agentes locales)
            </p>
            <p className="text-xs text-fg-muted">
              {runners.length === 0 ? (
                <>
                  Sin runner.{canInstall && <> <Link href="/local-agents" className="k-link">Instalarlo</Link></>}
                </>
              ) : runnerOnline ? (
                `Conectada: usó el tablero ${ago(lastRunner, now)}.`
              ) : (
                <>
                  Sin actividad desde {ago(lastRunner, now)}.{canInstall && <> <Link href="/local-agents" className="k-link">Instalar el runner</Link></>}
                </>
              )}
            </p>
          </section>
        )}

        {usage && (
          <section className="flex flex-col gap-1 rounded-lg border border-line bg-surface px-4 py-3" aria-label="Gasto de IA">
            <p className="text-[13px] font-medium text-fg-strong">Gasto de IA</p>
            <p className="text-xs text-fg-muted">
              Hoy {usd(today)} · este mes {usage.costUsd == null ? "sin precio para algún modelo" : usd(usage.costUsd)}
              {usage.budgetUsd != null && ` de ${usd(usage.budgetUsd)}`}.
            </p>
          </section>
        )}

        <section className="flex flex-col gap-2" aria-label="Lo que hicieron los agentes">
          <h2 className="text-[13px] font-medium text-fg-strong">Lo que hicieron los agentes</h2>
          {recent.length === 0 ? (
            <p className="text-xs text-fg-faint">Ningún agente trabajó en las últimas 24 horas.</p>
          ) : (
            <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
              {recent.slice(0, 20).map((r) => (
                <li key={r.id} className="flex gap-3 px-4 py-2.5">
                  <span aria-hidden className={`mt-1.5 size-2 shrink-0 rounded-full ${DOT[r.status]}`} />
                  <div className="min-w-0 flex-1">
                    <p className="text-[13px] text-fg-strong">
                      {nameOf(r.agentId)} ·{" "}
                      <Link href={`/projects/${r.projectKey}?issue=${r.issueKey}`} className="font-mono hover:underline">
                        {r.issueKey}
                      </Link>{" "}
                      <span className="text-xs text-fg-faint">{ago(r.createdAt, now)}</span>
                    </p>
                    {(r.error || r.summary) && <p className="line-clamp-2 text-xs text-fg-muted">{r.error ?? r.summary}</p>}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
