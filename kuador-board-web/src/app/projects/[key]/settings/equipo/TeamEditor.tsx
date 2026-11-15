"use client";

import { Plus, UserMinus } from "lucide-react";
import Link from "next/link";
import { useState, useTransition } from "react";

import { addToTeamAction, createTeamAgentAction, removeFromTeamAction, setAgentProfileAction } from "@/app/actions";
import { useAccess } from "@/components/shell/AccessContext";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { Field, SelectInput, TextArea, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import { MODEL_LABEL } from "@/lib/labels";
import type { AgentMode, TeamAgent, WorkspaceAiStatus } from "@/lib/types";

const MODE_CHOICES: { value: AgentMode; label: string; hint: string }[] = [
  { value: "SERVER", label: "En el tablero", hint: "Trabaja con la clave de IA de la empresa cuando le asignas una tarjeta o cuando llega a su etapa; al terminar la pasa a la siguiente." },
  { value: "LOCAL", label: "En la PC de una persona", hint: "Lo trabaja el Claude Code o Codex de alguien del equipo con /pendientes." },
];


type Role = { key: string; name: string; description: string };

/** Equipo de agentes del tablero: dónde trabaja cada uno, sumar uno del espacio o crear uno nuevo. */
export function TeamEditor({ projectKey, software, team: initial, roles, ai }: { projectKey: string; software: boolean; team: TeamAgent[]; roles: Role[]; ai: WorkspaceAiStatus }) {
  const { canAdminister } = useAccess();
  const [team, setTeam] = useState(initial);
  const [creating, setCreating] = useState(false);
  const [pending, start] = useTransition();
  const members = team.filter((a) => a.inTeam);
  const others = team.filter((a) => !a.inTeam);
  const serverWithoutKey = !ai.configured && members.some((a) => a.mode === "SERVER");

  const act = (work: () => Promise<{ ok: true; data: TeamAgent[] } | { ok: false; error: string }>, done?: string) =>
    start(async () => {
      const r = await work();
      if (!r.ok) {
        notify.error("No se pudo guardar", r.error);
        return;
      }
      setTeam(r.data);
      if (done) notify.ok(done);
    });

  return (
    <div className="flex flex-col gap-5">
      {serverWithoutKey && (
        <p role="status" className="rounded-md bg-warn/10 px-3 py-2 text-[13px] text-fg">
          Hay agentes que trabajan en el tablero, pero la empresa todavía no tiene clave de IA.{" "}
          <Link href="/conexiones" className="k-link">Configúrala en Conexiones → IA</Link>.
        </p>
      )}

      {members.length === 0 ? (
        <p className="text-[13px] text-fg-faint">Este tablero no tiene agentes todavía.</p>
      ) : (
        <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
          {members.map((a) => (
            <AgentRow
              key={a.id}
              agent={a}
              models={ai.models}
              defaultModel={ai.defaultModel}
              disabled={pending}
              canConfigure={canAdminister}
              onChange={(next) => setTeam((t) => t.map((x) => (x.id === next.id ? { ...next, inTeam: true } : x)))}
              onRemove={() => act(() => removeFromTeamAction(projectKey, a.id), `${a.name} salió del equipo`)}
            />
          ))}
        </ul>
      )}

      <div className="flex flex-wrap items-end gap-3">
        {others.length > 0 && (
          <div className="w-72">
            <Field label="Sumar un agente del espacio">
              {(id) => (
                <SelectInput
                  id={id}
                  placeholder="Elegir agente…"
                  value={null}
                  disabled={pending}
                  onChange={(v) => v && act(() => addToTeamAction(projectKey, v), "Agente sumado al equipo")}
                  options={others.map((a) => ({ value: a.id, label: `${a.name} · ${a.roleName}` }))}
                />
              )}
            </Field>
          </div>
        )}
        {!creating && canAdminister && (
          <Button icon={Plus} onClick={() => setCreating(true)}>
            Crear agente
          </Button>
        )}
      </div>

      {creating && (
        <CreateAgent
          roles={roles}
          models={ai.models}
          defaultMode={software ? "LOCAL" : "SERVER"}
          pending={pending}
          onCancel={() => setCreating(false)}
          onCreate={(input) =>
            start(async () => {
              const r = await createTeamAgentAction(projectKey, input);
              if (!r.ok) {
                notify.error("No se pudo crear el agente", r.error);
                return;
              }
              setTeam(r.data);
              setCreating(false);
              notify.ok("Agente creado y sumado al equipo");
            })
          }
        />
      )}
    </div>
  );
}

function AgentRow({
  agent,
  models,
  defaultModel,
  disabled,
  canConfigure,
  onChange,
  onRemove,
}: {
  agent: TeamAgent;
  models: string[];
  defaultModel: string;
  disabled: boolean;
  /** Dónde trabaja y con qué modelo vale para todo el espacio: solo quien administra lo cambia. */
  canConfigure: boolean;
  onChange: (a: TeamAgent) => void;
  onRemove: () => void;
}) {
  const [saving, start] = useTransition();
  const save = (mode: AgentMode, model: string | null) =>
    start(async () => {
      const r = await setAgentProfileAction(agent.id, { mode, model });
      if (!r.ok) {
        notify.error("No se pudo guardar", r.error);
        return;
      }
      onChange(r.data);
    });
  return (
    <li className="flex flex-wrap items-center gap-x-4 gap-y-3 px-4 py-3">
      <Avatar member={{ name: agent.name, avatarColor: agent.avatarColor, kind: "AGENT" }} size="md" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-[13px] font-medium text-fg-strong">{agent.name}</p>
        <p className="truncate text-xs text-fg-faint">{agent.roleName}</p>
      </div>
      <div className="w-56">
        <SelectInput<AgentMode>
          ariaLabel={`Dónde trabaja ${agent.name}`}
          size="sm"
          value={agent.mode}
          disabled={disabled || saving || !canConfigure}
          onChange={(v) => v && save(v, agent.model)}
          options={MODE_CHOICES.map((c) => ({ value: c.value, label: c.label }))}
        />
      </div>
      {/* El modelo vale cuando trabaja con la IA de la empresa: en el tablero y en las automatizaciones (p. ej. el Agente PO). */}
      {(agent.mode === "SERVER" || models.length > 0) && (
        <div className="w-60">
          <SelectInput
            ariaLabel={`Modelo de ${agent.name}`}
            size="sm"
            value={agent.model ?? "DEFAULT"}
            disabled={disabled || saving || !canConfigure}
            onChange={(v) => v && save(agent.mode, v === "DEFAULT" ? null : v)}
            options={[{ value: "DEFAULT", label: `El del espacio (${MODEL_LABEL[defaultModel]?.split(" (")[0] ?? defaultModel})` }, ...models.map((m) => ({ value: m, label: MODEL_LABEL[m] ?? m }))]}
          />
        </div>
      )}
      <Button size="sm" tone="ghost" icon={UserMinus} iconOnly label={`Quitar a ${agent.name} del equipo`} disabled={disabled} onClick={onRemove} />
    </li>
  );
}

function CreateAgent({
  roles,
  models,
  defaultMode,
  pending,
  onCancel,
  onCreate,
}: {
  roles: Role[];
  models: string[];
  defaultMode: AgentMode;
  pending: boolean;
  onCancel: () => void;
  onCreate: (input: { name: string; role: string; description: string | null; mode: AgentMode; model: string | null }) => void;
}) {
  const [role, setRole] = useState<string | null>(roles.find((r) => r.key === "ANALYST")?.key ?? roles[0]?.key ?? null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [mode, setMode] = useState<AgentMode>(defaultMode);
  const [model, setModel] = useState<string>("DEFAULT");
  const chosen = roles.find((r) => r.key === role);
  return (
    <form
      className="flex flex-col gap-4 rounded-lg border border-line bg-surface p-4"
      onSubmit={(e) => {
        e.preventDefault();
        if (!role) return;
        onCreate({ name: name.trim(), role, description: description.trim() || null, mode, model: mode === "SERVER" && model !== "DEFAULT" ? model : null });
      }}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Rol" hint={chosen?.description}>
          {(id) => <SelectInput id={id} value={role} onChange={setRole} options={roles.map((r) => ({ value: r.key, label: r.name }))} />}
        </Field>
        <Field label="Nombre" hint="Vacío = «Agente» y el nombre del rol.">
          {(id) => <TextInput id={id} maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder="Agente de ventas" />}
        </Field>
      </div>
      <Field label="Especialidad" hint="Opcional: qué hace en este equipo, además del manual de su rol. Ej.: «Informes de ventas de la florería en dólares».">
        {(id) => <TextArea id={id} rows={3} maxLength={4000} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <ChoiceCards name="agent-mode" label="Dónde trabaja" columns={2} value={mode} onChange={setMode} choices={MODE_CHOICES} />
      {mode === "SERVER" && (
        <div className="w-72">
          <Field label="Modelo">
            {(id) => (
              <SelectInput
                id={id}
                value={model}
                onChange={(v) => v && setModel(v)}
                options={[{ value: "DEFAULT", label: "El del espacio" }, ...models.map((m) => ({ value: m, label: MODEL_LABEL[m] ?? m }))]}
              />
            )}
          </Field>
        </div>
      )}
      <div className="flex justify-end gap-2">
        <Button tone="ghost" onClick={onCancel}>Cancelar</Button>
        <Button type="submit" tone="primary" loading={pending} disabled={!role}>Crear agente</Button>
      </div>
    </form>
  );
}
