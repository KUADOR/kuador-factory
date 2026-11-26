"use client";

import { Plus, Trash2, UserMinus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";

import {
  addTeamMemberAction,
  createAgentInTeamAction,
  createTeamAction,
  deleteTeamAction,
  removeTeamMemberAction,
  renameTeamAction,
  teamAgentsAction,
} from "@/app/actions";
import { AgentRow, CreateAgent, type Role } from "@/components/agents/AgentControls";
import { PageBody, PageHeader } from "@/components/shell/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Field, SelectInput, TextInput } from "@/components/ui/Field";
import { SidePanel } from "@/components/ui/SidePanel";
import { notify } from "@/components/ui/Toaster";
import type { Member, Team, TeamAgent, WorkspaceAiStatus } from "@/lib/types";

type Board = { key: string; name: string };

const count = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** Organización → Equipos: quién trabaja junto. Un equipo se arma una vez y atiende uno o varios tableros. */
export function TeamsView({
  teams,
  people,
  roles,
  ai,
  boards,
  initialOpen,
  creating: initialCreating,
}: {
  teams: Team[];
  people: Member[];
  roles: Role[];
  ai: WorkspaceAiStatus;
  boards: Board[];
  initialOpen: string | null;
  creating: boolean;
}) {
  const [openId, setOpenId] = useState<string | null>(initialOpen);
  const [creating, setCreating] = useState(initialCreating);
  const opened = teams.find((t) => t.id === openId) ?? null;
  return (
    <>
      <PageHeader
        title="Equipos"
        description="Quién trabaja junto. Un equipo atiende uno o varios tableros."
        actions={
          <Button tone="primary" icon={Plus} onClick={() => setCreating(true)}>
            Nuevo equipo
          </Button>
        }
      />
      <PageBody>
        {teams.length === 0 ? (
          <p className="text-[13px] text-fg-faint">Aún no hay equipos.</p>
        ) : (
          <ul className="divide-y divide-line overflow-hidden rounded-lg border border-line bg-surface">
            {teams.map((t) => {
              const agents = t.members.filter((m) => m.agent).length;
              return (
                <li key={t.id}>
                  <button
                    type="button"
                    onClick={() => setOpenId(t.id)}
                    className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3 text-left hover:bg-hover"
                  >
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] font-medium text-fg-strong">{t.name}</span>
                      <span className="text-xs text-fg-faint">
                        {count(t.members.length - agents, "persona", "personas")} · {count(agents, "agente", "agentes")}
                      </span>
                    </span>
                    <span className="flex flex-wrap items-center gap-1.5">
                      {t.boards.length === 0 && <span className="text-xs text-fg-faint">Ningún tablero</span>}
                      {t.boards.map((b) => (
                        <span key={b} className="rounded bg-sunken px-1.5 py-0.5 font-mono text-[11px] text-fg-strong">
                          {b}
                        </span>
                      ))}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </PageBody>

      <SidePanel open={opened != null} onClose={() => setOpenId(null)} title={opened?.name ?? "Equipo"}>
        {opened && <TeamPanel key={opened.id} team={opened} people={people} roles={roles} ai={ai} boards={boards} onDeleted={() => setOpenId(null)} />}
      </SidePanel>

      <SidePanel open={creating} onClose={() => setCreating(false)} width="narrow" title="Nuevo equipo">
        {creating && (
          <NewTeam
            onCreated={(t) => {
              setCreating(false);
              setOpenId(t.id);
            }}
            onCancel={() => setCreating(false)}
          />
        )}
      </SidePanel>
    </>
  );
}

function NewTeam({ onCreated, onCancel }: { onCreated: (t: Team) => void; onCancel: () => void }) {
  const [name, setName] = useState("");
  const [pending, start] = useTransition();
  const router = useRouter();
  return (
    <form
      className="flex flex-col gap-5 px-5 py-5"
      onSubmit={(e) => {
        e.preventDefault();
        start(async () => {
          const r = await createTeamAction({ name: name.trim() });
          if (!r.ok) {
            notify.error("No se pudo crear el equipo", r.error);
            return;
          }
          router.refresh();
          onCreated(r.data);
        });
      }}
    >
      <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={140} value={name} onChange={(e) => setName(e.target.value)} placeholder="team01" />}</Field>
      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button tone="ghost" onClick={onCancel}>
          Cancelar
        </Button>
        <Button type="submit" tone="primary" loading={pending} disabled={!name.trim()}>
          Crear equipo
        </Button>
      </div>
    </form>
  );
}

function TeamPanel({
  team: initial,
  people,
  roles,
  ai,
  boards,
  onDeleted,
}: {
  team: Team;
  people: Member[];
  roles: Role[];
  ai: WorkspaceAiStatus;
  boards: Board[];
  onDeleted: () => void;
}) {
  const router = useRouter();
  const [team, setTeam] = useState(initial);
  const [name, setName] = useState(initial.name);
  const [agents, setAgents] = useState<TeamAgent[] | null>(null);
  const [creatingAgent, setCreatingAgent] = useState(false);
  const [pending, start] = useTransition();

  useEffect(() => {
    let live = true;
    teamAgentsAction(initial.id).then((r) => {
      if (!live) return;
      if (r.ok) setAgents(r.data);
      else notify.error("No se pudieron cargar los agentes", r.error);
    });
    return () => {
      live = false;
    };
  }, [initial.id]);

  const humans = team.members.filter((m) => !m.agent);
  const outside = people.filter((p) => !team.members.some((m) => m.id === p.id));
  const inTeam = (agents ?? []).filter((a) => a.inTeam);
  const otherAgents = (agents ?? []).filter((a) => !a.inTeam);
  const boardName = (key: string) => boards.find((b) => b.key === key)?.name ?? key;

  const change = (work: () => Promise<{ ok: true; data: Team } | { ok: false; error: string }>, done: string, reloadAgents = false) =>
    start(async () => {
      const r = await work();
      if (!r.ok) {
        notify.error("No se pudo guardar", r.error);
        return;
      }
      setTeam(r.data);
      if (reloadAgents) {
        const a = await teamAgentsAction(team.id);
        if (a.ok) setAgents(a.data);
      }
      router.refresh();
      notify.ok(done);
    });

  return (
    <div className="flex flex-col gap-8 px-5 py-5">
      <form
        className="flex items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          change(() => renameTeamAction(team.id, name.trim()), "Equipo guardado");
        }}
      >
        <div className="flex-1">
          <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={140} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        </div>
        {name.trim() !== team.name && (
          <Button type="submit" loading={pending} disabled={!name.trim()}>
            Guardar
          </Button>
        )}
      </form>

      <p className="text-[13px] text-fg-muted">
        {team.boards.length === 0 ? (
          "Todavía no atiende ningún tablero."
        ) : (
          <>
            Atiende:{" "}
            {team.boards.map((b, i) => (
              <span key={b}>
                {i > 0 && ", "}
                <Link href={`/projects/${b}`} className="k-link">
                  {boardName(b)}
                </Link>
              </span>
            ))}
          </>
        )}
      </p>

      <section className="flex flex-col gap-3">
        <h3 className="text-sm font-semibold text-fg-strong">Personas</h3>
        {humans.length === 0 && <p className="text-[13px] text-fg-faint">Nadie todavía.</p>}
        <ul className="divide-y divide-line">
          {humans.map((p) => (
            <li key={p.id} className="flex items-center gap-3 py-2">
              <Avatar member={{ name: p.name, avatarColor: p.avatarColor, kind: "HUMAN" }} size="md" />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[13px] font-medium text-fg-strong">{p.name}</span>
                <span className="block truncate text-xs text-fg-faint">{p.role}</span>
              </span>
              <Button
                size="sm"
                tone="ghost"
                icon={UserMinus}
                iconOnly
                label={`Quitar a ${p.name} del equipo`}
                disabled={pending}
                onClick={() => change(() => removeTeamMemberAction(team.id, p.id), `${p.name} salió del equipo`)}
              />
            </li>
          ))}
        </ul>
        {outside.length > 0 && (
          <div className="w-72">
            <SelectInput
              ariaLabel="Sumar una persona"
              placeholder="Sumar una persona…"
              value={null}
              disabled={pending}
              onChange={(v) => v && change(() => addTeamMemberAction(team.id, v), "Persona sumada al equipo")}
              options={outside.map((p) => ({ value: p.id, label: p.name }))}
            />
          </div>
        )}
      </section>

      <section className="flex flex-col gap-3">
        <h3 className="text-sm font-semibold text-fg-strong">Agentes</h3>
        {agents == null ? (
          <p className="text-[13px] text-fg-faint">Cargando…</p>
        ) : (
          <>
            {inTeam.length === 0 && <p className="text-[13px] text-fg-faint">Ningún agente todavía.</p>}
            <ul className="divide-y divide-line">
              {inTeam.map((a) => (
                <AgentRow
                  key={a.id}
                  agent={a}
                  models={ai.models}
                  defaultModel={ai.defaultModel}
                  disabled={pending}
                  canConfigure
                  projectKey={team.boards[0] ?? ""}
                  onChange={(next) => setAgents((list) => (list ?? []).map((x) => (x.id === next.id ? { ...next, inTeam: true } : x)))}
                  onRemove={() => change(() => removeTeamMemberAction(team.id, a.id), `${a.name} salió del equipo`, true)}
                />
              ))}
            </ul>
            <div className="flex flex-wrap items-center gap-3">
              {otherAgents.length > 0 && (
                <div className="w-72">
                  <SelectInput
                    ariaLabel="Sumar un agente"
                    placeholder="Sumar un agente…"
                    value={null}
                    disabled={pending}
                    onChange={(v) => v && change(() => addTeamMemberAction(team.id, v), "Agente sumado al equipo", true)}
                    options={otherAgents.map((a) => ({ value: a.id, label: `${a.name} · ${a.roleName}` }))}
                  />
                </div>
              )}
              {!creatingAgent && (
                <Button icon={Plus} onClick={() => setCreatingAgent(true)}>
                  Crear agente
                </Button>
              )}
            </div>
            {creatingAgent && (
              <CreateAgent
                roles={roles}
                models={ai.models}
                defaultMode="SERVER"
                pending={pending}
                onCancel={() => setCreatingAgent(false)}
                onCreate={(input) =>
                  start(async () => {
                    const r = await createAgentInTeamAction(team.id, input);
                    if (!r.ok) {
                      notify.error("No se pudo crear el agente", r.error);
                      return;
                    }
                    setAgents(r.data);
                    setCreatingAgent(false);
                    router.refresh();
                    notify.ok("Agente creado y sumado al equipo");
                  })
                }
              />
            )}
          </>
        )}
      </section>

      {team.boards.length === 0 && (
        <div className="border-t border-line pt-4">
          <Button
            tone="danger"
            icon={Trash2}
            disabled={pending}
            onClick={() => {
              if (!window.confirm(`¿Borrar el equipo «${team.name}»? No atiende ningún tablero.`)) return;
              start(async () => {
                const r = await deleteTeamAction(team.id);
                if (!r.ok) {
                  notify.error("No se pudo borrar", r.error);
                  return;
                }
                notify.ok("Equipo borrado");
                router.refresh();
                onDeleted();
              });
            }}
          >
            Borrar equipo
          </Button>
        </div>
      )}
    </div>
  );
}
