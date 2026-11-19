"use client";

import { Plus, Sparkle } from "lucide-react";
import { useState, useTransition } from "react";

import { createMemberAction, inviteAction } from "@/app/actions";
import { AgentRunItem } from "@/components/agents/AgentRunItem";
import { PageHeader } from "@/components/shell/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { Field, SelectInput, TextArea, TextInput } from "@/components/ui/Field";
import { ChipPicker } from "@/components/ui/ChipPicker";
import { Badge, Section } from "@/components/ui/Section";
import { SidePanel } from "@/components/ui/SidePanel";
import { notify } from "@/components/ui/Toaster";
import { useAccess } from "@/components/shell/AccessContext";
import { ACCESS_LABEL, defaultAccessFor } from "@/lib/access";
import { roleLabel } from "@/lib/labels";
import type { Account, AgentRole, AgentRun, AgentToken, AgentsStatus, Invitation, Member, MemberAccess, MemberKind, Project } from "@/lib/types";

import { AgentTokens } from "./AgentTokens";
import { HumanAccess } from "./HumanAccess";
import { MemberAccessPicker, accessChoices } from "./MemberAccessPicker";

type Props = {
  members: Member[];
  accounts: Account[];
  tokens: AgentToken[];
  status: AgentsStatus;
  runs: AgentRun[];
  roles: AgentRole[];
  invitations: Invitation[];
  projects: Project[];
};

/** Equipo del espacio: personas y agentes en listas; el detalle de cada uno se abre al costado. */
export function TeamView({ members, accounts, tokens, status, runs, roles, invitations, projects }: Props) {
  const [openId, setOpenId] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const { me, canAdminister } = useAccess();
  const roleOf = (key: string) => roles.find((r) => r.key === key);
  const open = members.find((m) => m.id === openId);

  const groups = [
    { title: "Personas", list: members.filter((m) => m.kind === "HUMAN") },
    { title: "Agentes", list: members.filter((m) => m.kind === "AGENT") },
  ];

  return (
    <>
      <PageHeader
        title="Equipo"
        actions={
          canAdminister && (
            <Button tone="primary" size="sm" icon={Plus} onClick={() => setAdding(true)}>
              Agregar miembro
            </Button>
          )
        }
      />
      <div className="flex flex-col gap-5 px-4 py-5 sm:px-6 sm:py-6">
        {groups.map(({ title, list }) => (
          <Section key={title} title={`${title} · ${list.length}`} flush>
            <ul className="divide-y divide-line">
              {list.map((m) => {
                const role = roleOf(m.role);
                const access = m.kind === "HUMAN" ? accounts.find((a) => a.memberId === m.id)?.email : null;
                const active = tokens.filter((t) => t.memberId === m.id).length;
                const running = runs.find((r) => r.agentId === m.id && r.status === "RUNNING");
                return (
                  <li key={m.id}>
                    <button type="button" onClick={() => setOpenId(m.id)} className="grid w-full grid-cols-[2rem_minmax(0,1fr)_auto] items-center gap-3 px-5 py-2.5 text-left hover:bg-hover sm:grid-cols-[2rem_minmax(0,14rem)_minmax(0,1fr)_auto]">
                      <Avatar member={m} size="md" />
                      <span className="min-w-0">
                        <span className="block truncate text-[13px] font-medium text-fg-strong">{m.name}</span>
                        <span className="block truncate text-xs text-fg-faint">{roleLabel(m.role, role?.name)}</span>
                      </span>
                      <span className="hidden truncate text-xs text-fg-muted sm:block">{m.description}</span>
                      <span className="flex flex-wrap items-center justify-end gap-2">
                        {running && <Badge tone="sol">Trabajando en {running.issueKey}</Badge>}
                        {m.kind === "HUMAN" && m.access !== "EDIT" && <Badge tone={m.access === "VIEW" ? "neutral" : "sol"}>{ACCESS_LABEL[m.access]}</Badge>}
                        {m.kind === "AGENT" && role && role.automation !== "NONE" && (
                          <Badge tone="sol">
                            <Sparkle className="size-3" aria-hidden /> Automatizado
                          </Badge>
                        )}
                        {m.kind === "HUMAN" ? (
                          <Badge tone={access ? "ok" : invitations.some((i) => i.memberId === m.id) ? "sol" : "neutral"}>
                            {access ? "Con acceso" : invitations.some((i) => i.memberId === m.id) ? "Invitación enviada" : "Sin acceso"}
                          </Badge>
                        ) : (
                          <Badge tone={active ? "ok" : "neutral"}>{active ? `${active} token${active > 1 ? "s" : ""}` : "Sin token"}</Badge>
                        )}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </Section>
        ))}
      </div>

      <SidePanel open={open != null} onClose={() => setOpenId(null)} width="narrow" title={open ? <span className="flex items-center gap-2"><Avatar member={open} size="sm" /> {open.name}</span> : ""}>
        {open && (
          <div className="flex flex-col gap-6 px-5 py-5">
            <dl className="grid grid-cols-[6rem_minmax(0,1fr)] gap-x-3 gap-y-2 text-[13px]">
              <dt className="text-fg-faint">Tipo</dt>
              <dd className="text-fg">{open.kind === "AGENT" ? "Agente" : "Persona"}</dd>
              <dt className="text-fg-faint">Rol</dt>
              <dd className="text-fg">
                {roleLabel(open.role, roleOf(open.role)?.name)}
              </dd>
            </dl>
            {open.description && <p className="text-[13px] leading-relaxed text-fg">{open.description}</p>}
            {open.kind === "HUMAN" && !canAdminister && (
              <p className="text-[13px] text-fg-muted">Nivel de acceso: {ACCESS_LABEL[open.access]}</p>
            )}
            {!canAdminister ? null : open.kind === "AGENT" ? (
              <>
                <AgentAutomation role={roleOf(open.role)} status={status} runs={runs.filter((r) => r.agentId === open.id).slice(0, 5)} />
                <div className="flex flex-col gap-2">
                  <h3 className="text-xs font-medium text-fg-muted">Tokens de servicio</h3>
                  <AgentTokens memberId={open.id} tokens={tokens.filter((t) => t.memberId === open.id)} />
                </div>
              </>
            ) : (
              <div className="flex flex-col gap-4">
                <div className="flex flex-col gap-2">
                  <h3 className="text-xs font-medium text-fg-muted">Nivel de acceso</h3>
                  <MemberAccessPicker key={open.id} memberId={open.id} value={open.access} self={open.id === me?.id} />
                </div>
                <div className="flex flex-col gap-2">
                  <h3 className="text-xs font-medium text-fg-muted">Acceso al tablero</h3>
                  <HumanAccess
                    key={open.id}
                    memberId={open.id}
                    email={accounts.find((a) => a.memberId === open.id)?.email ?? null}
                    invitation={invitations.find((i) => i.memberId === open.id) ?? null}
                  />
                </div>
              </div>
            )}
          </div>
        )}
      </SidePanel>

      <SidePanel open={adding} onClose={() => setAdding(false)} width="narrow" title="Agregar miembro">
        {adding && <AddMember roles={roles} projects={projects} onDone={() => setAdding(false)} />}
      </SidePanel>
    </>
  );
}

function AgentAutomation({ role, status, runs }: { role: AgentRole | undefined; status: AgentsStatus; runs: AgentRun[] }) {
  const automated = role && role.automation !== "NONE";
  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-xs font-medium text-fg-muted">Automatización</h3>
      <p className="text-[13px] leading-relaxed text-fg">
        {!automated
          ? "Su rol no trabaja solo."
          : status.enabled
            ? `Activa · modelo ${status.model}`
            : `Desactivada: ${status.reason}`}
      </p>
      {runs.length > 0 && (
        <ul className="flex flex-col gap-1.5">
          {runs.map((r) => (
            <AgentRunItem key={r.id} run={r} showIssue />
          ))}
        </ul>
      )}
    </div>
  );
}

const COLORS = ["#8fb4ff", "#34d399", "#c4a7ff", "#ff8a4c", "#ff6b78", "#5eead4", "#f9a8d4"];

function AddMember({ roles, projects, onDone }: { roles: AgentRole[]; projects: Project[]; onDone: () => void }) {
  const { me } = useAccess();
  // Tableros donde queda quien edita o solo mira (quien administra los ve todos).
  const [boards, setBoards] = useState<string[]>([]);
  const [kind, setKind] = useState<MemberKind>("AGENT");
  const [name, setName] = useState("");
  const [role, setRole] = useState<string | null>(roles[0]?.key ?? null);
  const [humanRole, setHumanRole] = useState("");
  // null = el que corresponde al rol (los clientes solo miran) hasta que se elija otro.
  const [access, setAccess] = useState<MemberAccess | null>(null);
  const effectiveAccess = access ?? defaultAccessFor(humanRole);
  const scoped = effectiveAccess === "EDIT" || effectiveAccess === "VIEW";
  const [description, setDescription] = useState("");
  const [inviteEmail, setInviteEmail] = useState("");
  const [color, setColor] = useState(COLORS[0]);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const agentRole = roles.find((r) => r.key === role);

  return (
    <form
      className="flex flex-col gap-4 px-5 py-5"
      onSubmit={(e) => {
        e.preventDefault();
        startTransition(async () => {
          // Con correo, la persona se crea invitada: le llega el enlace para elegir su contraseña.
          if (kind === "HUMAN" && inviteEmail.trim()) {
            const invited = await inviteAction({
              name,
              role: humanRole.trim().toUpperCase(),
              access: effectiveAccess,
              email: inviteEmail.trim(),
              projects: scoped ? boards : [],
            });
            if (invited.ok) {
              notify.ok(`Invitamos a ${invited.data.memberName}`, `Le llegó un enlace a ${invited.data.email}.`);
              onDone();
            } else setError(invited.error);
            return;
          }
          const result = await createMemberAction({
            name,
            kind,
            role: kind === "AGENT" ? (role ?? "") : humanRole.trim().toUpperCase(),
            description: (description.trim() || (kind === "AGENT" ? agentRole?.description : "")) ?? null,
            avatarColor: color,
            ...(kind === "HUMAN" ? { access: effectiveAccess } : {}),
          });
          if (result.ok) {
            notify.ok(`${result.data.name} se unió al equipo`);
            onDone();
          } else setError(result.error);
        });
      }}
    >
      <ChoiceCards
        name="member-kind"
        label="Tipo de miembro"
        columns={2}
        value={kind}
        onChange={setKind}
        choices={[
          { value: "AGENT", label: "Agente" },
          { value: "HUMAN", label: "Persona" },
        ]}
      />
      <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder={kind === "AGENT" ? "Agente Mobile · Flutter" : "Diana Pérez"} />}</Field>
      {kind === "AGENT" ? (
        <Field label="Rol" hint={agentRole?.description}>
          {(id) => <SelectInput id={id} options={roles.map((r) => ({ value: r.key, label: r.name }))} value={role} onChange={setRole} />}
        </Field>
      ) : (
        <Field label="Rol" hint="Mayúsculas, números o guion bajo.">
          {(id) => (
            <TextInput id={id} required pattern="[A-Za-z][A-Za-z0-9_]{1,39}" value={humanRole} onChange={(e) => setHumanRole(e.target.value.toUpperCase())} placeholder="CLIENTE" className="font-mono uppercase" />
          )}
        </Field>
      )}
      {kind === "HUMAN" && (
        <Field label="Correo para invitar" hint="Opcional. Le llega un enlace que vence en 7 días.">
          {(id) => <TextInput id={id} type="email" maxLength={254} value={inviteEmail} onChange={(e) => setInviteEmail(e.target.value)} placeholder="persona@empresa.com" />}
        </Field>
      )}
      {kind === "HUMAN" && <ChoiceCards name="new-member-access" label="Nivel de acceso" columns={2} value={effectiveAccess} onChange={setAccess} choices={accessChoices(me)} />}
      {kind === "HUMAN" && scoped && inviteEmail.trim() && projects.length > 0 && (
        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium text-fg-muted">Tableros que verá</span>
          <ChipPicker
            ariaLabel="Tableros que verá"
            placeholder="Sumar tablero…"
            empty="Ninguno"
            value={boards}
            onChange={setBoards}
            options={projects.map((p) => ({ value: p.key, label: `${p.key} · ${p.name}` }))}
          />
        </div>
      )}
      <Field label="Descripción" hint={kind === "AGENT" ? "Vacía = la del rol." : undefined}>
        {(id) => <TextArea id={id} rows={4} maxLength={4000} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <fieldset className="flex flex-col gap-1.5">
        <legend className="mb-1.5 text-xs font-medium text-fg-muted">Color del avatar</legend>
        <div className="flex gap-2">
          {COLORS.map((c) => (
            <label key={c} className="cursor-pointer rounded-full p-0.5 ring-2 ring-transparent has-[:checked]:ring-sol has-[:focus-visible]:ring-sol">
              <input type="radio" name="avatar-color" value={c} checked={color === c} onChange={() => setColor(c)} className="sr-only" aria-label={`Color ${c}`} />
              <span className="block size-6 rounded-full" style={{ backgroundColor: c }} />
            </label>
          ))}
        </div>
      </fieldset>
      {error && (
        <p role="alert" className="text-[13px] text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button tone="ghost" onClick={onDone}>
          Cancelar
        </Button>
        <Button tone="primary" type="submit" loading={pending}>
          {kind === "HUMAN" && inviteEmail.trim() ? "Invitar" : "Agregar"}
        </Button>
      </div>
    </form>
  );
}
