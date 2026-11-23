"use client";

import { Eye, Plus, UserMinus } from "lucide-react";
import Link from "next/link";
import { useState, useTransition } from "react";

import { addToTeamAction, agentPromptPreviewAction, createTeamAgentAction, removeFromTeamAction, setAgentProfileAction } from "@/app/actions";
import { useAccess } from "@/components/shell/AccessContext";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { Field, SelectInput, TextArea, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import { EFFORT_LABEL, MODEL_LABEL } from "@/lib/labels";
import { SidePanel } from "@/components/ui/SidePanel";
import type { AgentMode, AgentPromptPreview, TeamAgent, WorkspaceAiStatus } from "@/lib/types";

const MODE_CHOICES: { value: AgentMode; label: string; hint: string }[] = [
  { value: "SERVER", label: "En el tablero", hint: "Gasta la clave de IA de la empresa." },
  { value: "LOCAL", label: "Con el asistente de IA de una persona", hint: "En su computadora, con Claude u otro asistente conectado al tablero." },
];


const CODE_ONLY_LOCAL = "Escribe código: trabaja en la PC de una persona, donde está el repo.";

type Role = { key: string; name: string; description: string; worksOnCode: boolean };

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
        <p role="status" className="text-[13px] text-warn">
          Falta la clave de IA de la empresa.{" "}
          <Link href="/connections" className="k-link">Configúrala en Conexiones → IA</Link>.
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
              projectKey={projectKey}
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
  projectKey,
}: {
  projectKey: string;
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
  const save = (mode: AgentMode, model: string | null, effort: string | null = agent.effort) =>
    start(async () => {
      const r = await setAgentProfileAction(agent.id, { mode, model, effort });
      if (!r.ok) {
        notify.error("No se pudo guardar", r.error);
        return;
      }
      onChange(r.data);
    });
  const showModel = agent.mode === "SERVER" || models.length > 0;
  return (
    <li className="flex flex-col gap-3 px-4 py-3">
      {/* Quién es, siempre visible; los ajustes van debajo y se acomodan al ancho. */}
      <div className="flex items-center gap-3">
        <Avatar member={{ name: agent.name, avatarColor: agent.avatarColor, kind: "AGENT" }} size="md" />
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13px] font-medium text-fg-strong" title={agent.name}>{agent.name}</p>
          <p className="truncate text-xs text-fg-faint">{agent.roleName}</p>
        </div>
        {canConfigure && <PromptPreview agent={agent} projectKey={projectKey} />}
        <Button size="sm" tone="ghost" icon={UserMinus} iconOnly label={`Quitar a ${agent.name} del equipo`} disabled={disabled} onClick={onRemove} />
      </div>
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
      <div>
        <SelectInput<AgentMode>
          ariaLabel={`Dónde trabaja ${agent.name}`}
          size="sm"
          value={agent.mode}
          disabled={disabled || saving || !canConfigure || !agent.serverAllowed}
          onChange={(v) => v && save(v, agent.model)}
          options={MODE_CHOICES.map((c) => ({ value: c.value, label: c.label }))}
        />
        {!agent.serverAllowed && <p className="mt-1 text-xs text-fg-faint">{CODE_ONLY_LOCAL}</p>}
      </div>
      {/* El modelo vale cuando trabaja con la IA de la empresa: en el tablero y en las automatizaciones (p. ej. el Agente PO). */}
      {showModel && (
        <div>
          <SelectInput
            ariaLabel={`Modelo de ${agent.name}`}
            size="sm"
            value={agent.model ?? "DEFAULT"}
            disabled={disabled || saving || !canConfigure}
            onChange={(v) => v && save(agent.mode, v === "DEFAULT" ? null : v)}
            options={[{ value: "DEFAULT", label: defaultModelLabel(agent, models, defaultModel) }, ...models.map((m) => ({ value: m, label: MODEL_LABEL[m] ?? m }))]}
          />
        </div>
      )}
      {/* Cuánto razona: menos esfuerzo gasta menos tokens. Vale con Claude. */}
      {showModel && models.some((m) => m.startsWith("claude-")) && (
        <div>
          <SelectInput
            ariaLabel={`Esfuerzo de ${agent.name}`}
            size="sm"
            value={agent.effort ?? "DEFAULT"}
            disabled={disabled || saving || !canConfigure}
            onChange={(v) => v && save(agent.mode, agent.model, v === "DEFAULT" ? null : v)}
            options={[
              { value: "DEFAULT", label: agent.suggestedEffort ? `Sugerido: ${EFFORT_LABEL[agent.suggestedEffort]?.split(" (")[0] ?? agent.suggestedEffort}` : "Esfuerzo del modelo" },
              ...Object.entries(EFFORT_LABEL).map(([value, label]) => ({ value, label })),
            ]}
          />
        </div>
      )}
      </div>
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
  const onlyLocal = chosen?.worksOnCode ?? false;
  const effectiveMode: AgentMode = onlyLocal ? "LOCAL" : mode;
  return (
    <form
      className="flex flex-col gap-4 rounded-lg border border-line bg-surface p-4"
      onSubmit={(e) => {
        e.preventDefault();
        if (!role) return;
        onCreate({ name: name.trim(), role, description: description.trim() || null, mode: effectiveMode, model: effectiveMode === "SERVER" && model !== "DEFAULT" ? model : null });
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
      <Field label="Especialidad" hint="Opcional. Ej.: «Informes de ventas de la florería en dólares»">
        {(id) => <TextArea id={id} rows={3} maxLength={4000} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <ChoiceCards name="agent-mode" label="Dónde trabaja" columns={2} value={effectiveMode} onChange={setMode} choices={MODE_CHOICES} disabled={onlyLocal} />
      {onlyLocal && <p className="-mt-2 text-xs text-fg-faint">{CODE_ONLY_LOCAL}</p>}
      {effectiveMode === "SERVER" && (
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

/**
 * «Ver qué recibe»: el pedido que recibiría el agente para una tarjeta, con las Reglas de agentes del espacio ya
 * aplicadas, sin llamar al modelo. Para revisar las reglas y entender por qué un agente hizo lo que hizo.
 */
function PromptPreview({ agent, projectKey }: { agent: TeamAgent; projectKey: string }) {
  const [open, setOpen] = useState(false);
  const [issue, setIssue] = useState(`${projectKey}-`);
  const [preview, setPreview] = useState<AgentPromptPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, start] = useTransition();
  const load = () =>
    start(async () => {
      const r = await agentPromptPreviewAction(agent.id, issue.trim());
      if (r.ok) {
        setPreview(r.data);
        setError(null);
      } else {
        setPreview(null);
        setError(r.error);
      }
    });
  return (
    <>
      <Button size="sm" tone="ghost" icon={Eye} iconOnly label={`Ver qué recibe ${agent.name}`} onClick={() => setOpen(true)} />
      <SidePanel open={open} onClose={() => setOpen(false)} title={`Qué recibe ${agent.name}`}>
        <div className="flex flex-col gap-4 px-5 py-5">
          <form
            className="flex items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              load();
            }}
          >
            <Field label="Tarjeta" hint="No gasta IA.">
              {(id) => <TextInput id={id} value={issue} onChange={(e) => setIssue(e.target.value.toUpperCase())} className="w-40 font-mono" />}
            </Field>
            <Button type="submit" tone="primary" size="sm" loading={loading} disabled={!/^[A-Z][A-Z0-9]*-\d+$/.test(issue.trim())}>
              Ver
            </Button>
          </form>
          {error && (
            <p role="alert" className="text-[13px] text-danger">
              {error}
            </p>
          )}
          {preview && (
            <div className="flex flex-col gap-4">
              <p className="text-xs text-fg-muted">
                {preview.kind === "REFINE" ? "Refina la tarjeta (PO/Arquitecto)." : "Trabaja la tarjeta en el tablero."} Herramientas: {preview.tools.join(", ")}.{" "}
                {preview.note}
              </p>
              <PromptBlock title="Sistema (quién es y sus reglas)" text={preview.system} />
              <PromptBlock title="Mensaje (la tarjeta)" text={preview.user} />
              <p className="text-xs text-fg-faint">
                Las reglas se editan en <Link href="/catalog/rules" className="k-link">Catálogo → Reglas de agentes</Link>.
              </p>
            </div>
          )}
        </div>
      </SidePanel>
    </>
  );
}

function PromptBlock({ title, text }: { title: string; text: string }) {
  return (
    <section className="flex flex-col gap-1.5">
      <h3 className="text-xs font-medium text-fg-muted">{title}</h3>
      <pre className="max-h-[28rem] overflow-auto rounded-md border border-line bg-sunken px-3 py-2.5 font-mono text-[11px] leading-relaxed whitespace-pre-wrap text-fg">
        {text}
      </pre>
    </section>
  );
}

/** «Sugerido para el rol: Sonnet» si el rol trae uno que el espacio ofrece; si no, el del espacio. */
function defaultModelLabel(agent: TeamAgent, models: string[], defaultModel: string): string {
  const short = (m: string) => MODEL_LABEL[m]?.split(" (")[0] ?? m;
  if (agent.suggestedModel && models.includes(agent.suggestedModel)) return `Sugerido para el rol (${short(agent.suggestedModel)})`;
  return `El del espacio (${short(defaultModel)})`;
}
