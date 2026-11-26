"use client";

import { ArrowRight, Cloud, Laptop, Lock, TriangleAlert } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { clsx } from "clsx";

import { Avatar } from "@/components/ui/Avatar";
import { roleLabel } from "@/lib/labels";
import type { Member, Project, RunnerMachine } from "@/lib/types";

const ACCESS: { key: Member["access"]; label: string }[] = [
  { key: "OWNER", label: "Dueño" },
  { key: "ADMIN", label: "Administran" },
  { key: "EDIT", label: "Editan" },
  { key: "VIEW", label: "Solo miran" },
];

export interface OrgAgent {
  id: string;
  name: string;
  role: string;
  roleName: string;
  avatarColor: string;
  mode: "SERVER" | "LOCAL";
  /** Entregas en los últimos 30 días. */
  delivered: number;
}

/**
 * El organigrama del espacio en dos vistas. «Quién hace qué»: las personas por nivel de acceso, cada una con sus
 * máquinas (runner) y los agentes que atienden; los agentes del servidor aparte; y los que nadie atiende, en aviso.
 * «Flujo de cada tablero»: las etapas en orden, quién cubre cada una y quién aprueba sus puertas.
 */
export function OrgChart({
  people,
  agents,
  machines,
  projects,
  roleNames,
  workspaceName,
}: {
  people: Member[];
  agents: OrgAgent[];
  machines: RunnerMachine[];
  projects: Project[];
  roleNames: Record<string, string>;
  workspaceName: string;
}) {
  const [view, setView] = useState<"org" | "flow">("org");
  return (
    <div className="flex flex-col gap-4">
      <div role="tablist" aria-label="Vista del organigrama" className="flex gap-1">
        {(
          [
            ["org", "Quién hace qué"],
            ["flow", "Flujo de cada tablero"],
          ] as const
        ).map(([v, label]) => (
          <button
            key={v}
            type="button"
            role="tab"
            aria-selected={view === v}
            onClick={() => setView(v)}
            className={clsx(
              "rounded-md px-2.5 py-1.5 text-[13px]",
              view === v ? "bg-selected font-medium text-fg-strong shadow-[0_0_0_1px_var(--k-line)]" : "text-fg-muted hover:bg-hover hover:text-fg-strong",
            )}
          >
            {label}
          </button>
        ))}
      </div>
      {view === "org" ? (
        <WhoDoesWhat people={people} agents={agents} machines={machines} workspaceName={workspaceName} />
      ) : (
        <BoardFlows projects={projects} agents={agents} roleNames={roleNames} />
      )}
    </div>
  );
}

function AgentChip({ agent }: { agent: OrgAgent }) {
  return (
    <span className="inline-flex max-w-full min-w-0 items-center gap-1.5 rounded-full border border-line bg-surface py-0.5 pr-2.5 pl-0.5 text-xs text-fg">
      <Avatar member={{ ...agent, kind: "AGENT" }} size="xs" />
      <span className="min-w-0 truncate">{agent.name}</span>
      {agent.delivered > 0 && <span className="tabular shrink-0 whitespace-nowrap text-fg-faint">· {agent.delivered}</span>}
    </span>
  );
}

/** Rama del árbol: una línea vertical a la izquierda une a cada persona con lo que tiene debajo. */
function Branch({ children }: { children: React.ReactNode }) {
  return <div className="ml-3 flex flex-col gap-2 border-l border-line-strong pt-2 pl-4">{children}</div>;
}

function WhoDoesWhat({ people, agents, machines, workspaceName }: { people: Member[]; agents: OrgAgent[]; machines: RunnerMachine[]; workspaceName: string }) {
  const local = agents.filter((a) => a.mode === "LOCAL");
  const server = agents.filter((a) => a.mode === "SERVER");
  const serving = (m: RunnerMachine) => local.filter((a) => m.roles.includes(a.role));
  // Agentes locales que ninguna máquina prendida atiende: sus tarjetas esperarían.
  const covered = new Set(machines.filter((m) => m.online && !m.paused).flatMap((m) => m.roles));
  const orphan = local.filter((a) => !covered.has(a.role));

  return (
    <div className="flex flex-col gap-3">
      <div className="rounded-lg border border-line bg-surface px-4 py-3">
        <p className="text-[14px] font-semibold text-fg-strong">{workspaceName}</p>
        <p className="text-xs text-fg-muted">
          {people.length} persona{people.length === 1 ? "" : "s"} · {agents.length} agente{agents.length === 1 ? "" : "s"} · {machines.length} máquina
          {machines.length === 1 ? "" : "s"} con runner
        </p>
      </div>
      <Branch>
        {ACCESS.map(({ key, label }) => {
          const group = people.filter((p) => p.access === key);
          if (group.length === 0) return null;
          return (
            <section key={key} aria-label={label} className="flex flex-col gap-2">
              <p className="text-[11px] font-medium tracking-wide text-fg-faint uppercase">{label}</p>
              {group.map((p) => {
                const mine = machines.filter((m) => m.memberId === p.id);
                return (
                  <div key={p.id} className="flex flex-col">
                    <div className="flex items-center gap-2.5 rounded-lg border border-line bg-surface px-3 py-2">
                      <Avatar member={p} size="md" />
                      <span className="min-w-0">
                        <span className="block truncate text-[13px] font-medium text-fg-strong">{p.name}</span>
                        <span className="block truncate text-xs text-fg-muted">{roleLabel(p.role)}</span>
                      </span>
                    </div>
                    {mine.length > 0 && (
                      <Branch>
                        {mine.map((m) => (
                          <div key={m.id} className="flex flex-col gap-1.5 rounded-lg border border-dashed border-line-strong px-3 py-2">
                            <span className="flex items-center gap-2 text-xs">
                              <Laptop className="size-3.5 text-fg-muted" aria-hidden />
                              <span className="font-medium text-fg">{m.name}</span>
                              <span aria-hidden className={clsx("ml-auto size-2 rounded-full", !m.online ? "bg-fg-faint" : m.paused ? "bg-warn" : "bg-ok")} />
                              <span className="text-fg-muted">{!m.online ? "desconectada" : m.paused ? "en pausa" : "prendida"}</span>
                            </span>
                            <span className="flex flex-wrap gap-1.5">
                              {serving(m).length === 0 ? (
                                <span className="text-xs text-fg-faint">Ningún agente local con sus roles.</span>
                              ) : (
                                serving(m).map((a) => <AgentChip key={a.id} agent={a} />)
                              )}
                            </span>
                          </div>
                        ))}
                      </Branch>
                    )}
                  </div>
                );
              })}
            </section>
          );
        })}

        {server.length > 0 && (
          <section aria-label="Agentes en el servidor" className="flex flex-col gap-2">
            <p className="flex items-center gap-1.5 text-[11px] font-medium tracking-wide text-fg-faint uppercase">
              <Cloud className="size-3.5" aria-hidden /> En el servidor del tablero
            </p>
            <span className="flex flex-wrap gap-1.5">
              {server.map((a) => (
                <AgentChip key={a.id} agent={a} />
              ))}
            </span>
          </section>
        )}

        {orphan.length > 0 && (
          <section aria-label="Sin máquina" className="flex flex-col gap-1.5">
            <p className="flex flex-wrap items-center gap-1.5 text-xs text-warn">
              <TriangleAlert className="size-3.5" aria-hidden /> Sin una máquina prendida que los atienda: sus tarjetas esperan.
              <Link href="/local-agents" className="k-link">
                Instalar el runner
              </Link>
            </p>
            <span className="flex flex-wrap gap-1.5">
              {orphan.map((a) => (
                <AgentChip key={a.id} agent={a} />
              ))}
            </span>
          </section>
        )}
      </Branch>
    </div>
  );
}

function BoardFlows({ projects, agents, roleNames }: { projects: Project[]; agents: OrgAgent[]; roleNames: Record<string, string> }) {
  const [key, setKey] = useState(projects[0]?.key ?? "");
  const project = projects.find((p) => p.key === key) ?? projects[0];
  if (!project) return <p className="text-[13px] text-fg-muted">Todavía no hay tableros.</p>;
  const name = (r: string) => roleLabel(r, roleNames[r]);
  return (
    <div className="flex flex-col gap-3">
      {projects.length > 1 && (
        <div className="flex gap-1.5 overflow-x-auto" role="tablist" aria-label="Tablero">
          {projects.map((p) => (
            <button
              key={p.key}
              type="button"
              role="tab"
              aria-selected={p.key === project.key}
              onClick={() => setKey(p.key)}
              className={clsx(
                "h-8 shrink-0 rounded-full border px-3 text-[13px] whitespace-nowrap",
                p.key === project.key ? "border-sol/60 bg-sol/15 font-medium text-fg-strong" : "border-line text-fg-muted",
              )}
            >
              {p.name}
            </button>
          ))}
        </div>
      )}
      {/* En escritorio las etapas van de izquierda a derecha; en el celular, de arriba abajo. */}
      <ol className="flex flex-col gap-2 md:flex-row md:items-stretch md:overflow-x-auto md:pb-2">
        {project.columns.map((col, i) => {
          const stage = project.stages.find((s) => s.column === col.key);
          const covering = stage ? agents.filter((a) => stage.roles.includes(a.role)) : [];
          const humanRoles = stage ? stage.roles.filter((r) => !agents.some((a) => a.role === r)) : [];
          const reviewer = stage?.gate && stage.review && stage.review.mode !== "PERSON" ? agents.find((a) => a.role === (stage.review?.reviewerRole ?? "PR_REVIEWER")) : undefined;
          return (
            <li key={col.key} className="flex md:w-56 md:shrink-0 md:items-stretch">
              <div className="flex w-full flex-col gap-2 rounded-lg border border-line bg-surface px-3 py-2.5">
                <p className="flex items-center gap-1.5 text-[13px] font-medium text-fg-strong">
                  {stage?.gate && <Lock className="size-3.5 text-fg-faint" aria-label="Paso de aprobación" />}
                  {col.name}
                </p>
                {stage?.gate ? (
                  <div className="flex flex-col gap-1.5 text-xs text-fg-muted">
                    <span>
                      Aprueba: {stage.approvers.length ? stage.approvers.map(name).join(", ") : "cualquier persona del equipo"}
                    </span>
                    {reviewer && (
                      <span className="flex flex-wrap items-center gap-1.5">
                        Revisa primero: <AgentChip agent={reviewer} />
                      </span>
                    )}
                    {covering.length > 0 && (
                      <span className="flex flex-col items-start gap-1.5">
                        Prepara: {covering.map((a) => <AgentChip key={a.id} agent={a} />)}
                      </span>
                    )}
                  </div>
                ) : covering.length > 0 || humanRoles.length > 0 ? (
                  <div className="flex flex-col items-start gap-1.5">
                    {covering.map((a) => (
                      <span key={a.id} className="flex max-w-full min-w-0 items-center gap-1">
                        <AgentChip agent={a} />
                        {a.mode === "SERVER" ? <Cloud className="size-3 text-fg-faint" aria-label="En el servidor" /> : <Laptop className="size-3 text-fg-faint" aria-label="En la PC de una persona" />}
                      </span>
                    ))}
                    {humanRoles.map((r) => (
                      <span key={r} className="rounded-full border border-line px-2 py-0.5 text-xs text-fg-muted">
                        {name(r)}
                      </span>
                    ))}
                  </div>
                ) : (
                  <span className="text-xs text-fg-faint">{col.category === "DONE" ? "Terminado" : "Cualquiera del equipo"}</span>
                )}
              </div>
              {i < project.columns.length - 1 && <ArrowRight className="mx-1 hidden size-4 shrink-0 self-center text-fg-faint md:block" aria-hidden />}
            </li>
          );
        })}
      </ol>
      <p className="text-xs text-fg-faint">
        <Cloud className="inline size-3" aria-hidden /> servidor · <Laptop className="inline size-3" aria-hidden /> PC de una persona · número: entregas en 30 días. Cámbialo en{" "}
        <Link href={`/projects/${project.key}/flow`} className="k-link">
          Flujo
        </Link>{" "}
        y{" "}
        <Link href={`/projects/${project.key}/settings/people#agentes`} className="k-link">
          Equipo de agentes
        </Link>
        .
      </p>
    </div>
  );
}
