"use client";

import { ArrowLeft, ExternalLink, MessageSquare } from "lucide-react";
import Link from "next/link";
import { useState, useTransition } from "react";

import { addCommentAction, assignIssueAction, moveIssueAction } from "@/app/actions";
import { StageBar } from "@/components/board/StageBar";
import { useAccess } from "@/components/shell/AccessContext";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { SelectInput, TextArea } from "@/components/ui/Field";
import { Badge } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { AgentMode, AgentRun, Issue, Me, Member, Project } from "@/lib/types";

/** Lo que espera a alguien en la oficina, agrupado: qué aprobar, qué responder, qué destrabar y qué repartir. */
export interface Pending {
  review: Issue[];
  asking: { issue: Issue; agent: Member; question: string | null }[];
  stuck: { issue: Issue; agent: Member; error: string | null }[];
  inbox: Issue[];
  done: Issue[];
}

export type PendingFocus = "review" | "asking" | "stuck" | "inbox" | "done";

const SECTIONS: { key: PendingFocus; title: string; empty: string }[] = [
  { key: "review", title: "Por aprobar", empty: "Nada espera aprobación." },
  { key: "asking", title: "Preguntaron", empty: "Ningún agente espera respuesta." },
  { key: "stuck", title: "Trabados", empty: "Nadie está trabado." },
  { key: "inbox", title: "En la bandeja", empty: "No hay tarjetas sin responsable." },
  { key: "done", title: "Archivo", empty: "Todavía no se terminó nada." },
];

/** Los pendientes de la oficina con su acción a un clic: abrir la tarjeta o, en la bandeja, asignarla ahí mismo. */
export function PendingPanel({
  pending,
  focus,
  project,
  assignable,
  onOpen,
}: {
  pending: Pending;
  focus: PendingFocus | null;
  project: Project;
  assignable: Member[];
  onOpen: (key: string) => void;
}) {
  const { canEdit } = useAccess();
  const order = focus ? [...SECTIONS.filter((s) => s.key === focus), ...SECTIONS.filter((s) => s.key !== focus)] : SECTIONS;
  const count = (k: PendingFocus) => (k === "asking" ? pending.asking.length : k === "stuck" ? pending.stuck.length : pending[k].length);
  return (
    <div className="flex flex-col gap-5 px-5 py-5">
      {order.map((s) => (
        <section key={s.key} className="flex flex-col gap-1.5" aria-label={s.title}>
          <div className="flex items-baseline justify-between gap-2">
            <h3 className="text-[13px] font-medium text-fg-strong">
              {s.title} <span className="tabular text-fg-faint">{count(s.key)}</span>
            </h3>
          </div>
          {count(s.key) === 0 ? (
            <p className="text-xs text-fg-faint">{s.empty}</p>
          ) : (
            <>
              <ul className="divide-y divide-line rounded-md border border-line">
                {s.key === "asking" &&
                  pending.asking.map(({ issue, agent, question }) => (
                    <Row key={issue.key} issue={issue} onOpen={onOpen} detail={`${agent.name}: ${question ?? "preguntó en la tarjeta"}`} />
                  ))}
                {s.key === "stuck" &&
                  pending.stuck.map(({ issue, agent, error }) => (
                    <Row key={issue.key} issue={issue} onOpen={onOpen} detail={`${agent.name}: ${error ?? "no pudo terminar"}`} tone="danger" />
                  ))}
                {s.key === "review" && pending.review.map((i) => <Row key={i.key} issue={i} onOpen={onOpen} detail={columnName(project, i.status)} />)}
                {s.key === "inbox" &&
                  pending.inbox.map((i) => (
                    <Row key={i.key} issue={i} onOpen={onOpen} detail={columnName(project, i.status)}>
                      {canEdit && <AssignSelect issue={i} project={project} members={assignable} compact />}
                    </Row>
                  ))}
                {s.key === "done" && pending.done.slice(0, 15).map((i) => <Row key={i.key} issue={i} onOpen={onOpen} detail={columnName(project, i.status)} />)}
              </ul>
            </>
          )}
        </section>
      ))}
    </div>
  );
}

function Row({ issue, onOpen, detail, tone, children }: { issue: Issue; onOpen: (key: string) => void; detail?: string; tone?: "danger"; children?: React.ReactNode }) {
  return (
    <li className="flex flex-wrap items-center gap-x-2 gap-y-1.5 px-3 py-2">
      <button type="button" onClick={() => onOpen(issue.key)} className="flex min-w-0 flex-1 flex-col items-start text-left hover:opacity-80">
        <span className="flex w-full min-w-0 items-center gap-2 text-[13px]">
          <span className="tabular shrink-0 font-mono text-xs whitespace-nowrap text-fg-muted">{issue.key}</span>
          <span className="min-w-0 truncate text-fg">{issue.title}</span>
        </span>
        {detail && <span className={tone === "danger" ? "line-clamp-2 text-xs text-danger" : "line-clamp-2 text-xs text-fg-muted"}>{detail}</span>}
      </button>
      {children}
    </li>
  );
}

/**
 * Una tarjeta abierta en la oficina, con lo que se puede hacer sin ir al tablero: la acción de su etapa (aprobar,
 * tomar, entregar, reintentar), asignarla, moverla de columna y comentar. Si un agente preguntó, la respuesta se le
 * devuelve con un clic para que siga.
 */
export function CardPanel({
  issue,
  project,
  me,
  members,
  assignable,
  roleName,
  modes,
  runs,
  onBack,
}: {
  issue: Issue;
  project: Project;
  me: Me;
  members: Member[];
  assignable: Member[];
  roleName: (key: string) => string;
  modes: Record<string, AgentMode>;
  runs: AgentRun[];
  onBack: (() => void) | null;
}) {
  const { canEdit } = useAccess();
  const mine = runs.filter((r) => r.issueKey === issue.key).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const lastRun = mine[0];
  const byId = new Map(members.map((m) => [m.id, m]));
  const working = mine.find((r) => r.status === "RUNNING");
  const workingAgent = working ? byId.get(working.agentId) : undefined;
  const assignee = issue.assigneeId ? byId.get(issue.assigneeId) : undefined;
  // El agente que preguntó (su última ejecución terminó y la tarjeta volvió a una persona).
  const askedBy =
    lastRun && lastRun.status === "SUCCEEDED" && /pregunt/.test(lastRun.summary ?? "") && lastRun.agentId !== issue.assigneeId ? byId.get(lastRun.agentId) : undefined;

  return (
    <div className="flex flex-col gap-4 px-5 py-5">
      {onBack && (
        <button type="button" onClick={onBack} className="k-link flex w-fit items-center gap-1 text-xs">
          <ArrowLeft className="size-3.5" aria-hidden /> Volver
        </button>
      )}
      <div className="flex flex-col gap-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="tabular font-mono text-xs text-fg-muted">{issue.key}</span>
          <Badge tone="neutral">{columnName(project, issue.status)}</Badge>
        </div>
        <p className="text-[15px] font-medium text-fg-strong">{issue.title}</p>
        <p className="flex items-center gap-1.5 text-xs text-fg-muted">
          {assignee ? (
            <>
              <Avatar member={assignee} size="xs" /> {assignee.name}
            </>
          ) : (
            "Sin responsable"
          )}
        </p>
      </div>
      {lastRun && (lastRun.summary || lastRun.error) && (
        <div className="text-xs">
          <p className="mb-0.5 font-medium text-fg-muted">Lo último de {byId.get(lastRun.agentId)?.name ?? "un agente"}</p>
          <p className={lastRun.error ? "whitespace-pre-line text-danger" : "line-clamp-6 whitespace-pre-line text-fg"}>{lastRun.error ?? lastRun.summary}</p>
        </div>
      )}
      <StageBar issue={issue} project={project} me={me} members={members} roleName={roleName} modes={modes} lastRun={lastRun} workingAgent={workingAgent} />
      {canEdit && (
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-xs text-fg-muted">
            Responsable
            <AssignSelect issue={issue} project={project} members={assignable} />
          </label>
          <label className="flex flex-col gap-1 text-xs text-fg-muted">
            Paso
            <MoveSelect issue={issue} project={project} />
          </label>
        </div>
      )}
      {canEdit && <Reply issue={issue} project={project} askedBy={askedBy} />}
      <Link href={`/projects/${project.key}?issue=${issue.key}`} className="k-link flex w-fit items-center gap-1 text-xs">
        <ExternalLink className="size-3.5" aria-hidden /> Abrir en el tablero
      </Link>
    </div>
  );
}

/** Asignar una tarjeta a alguien del equipo (o dejarla sin responsable). */
function AssignSelect({ issue, project, members, compact }: { issue: Issue; project: Project; members: Member[]; compact?: boolean }) {
  const [pending, start] = useTransition();
  return (
    <div className={compact ? "w-44" : undefined}>
      <SelectInput
        ariaLabel={`Responsable de ${issue.key}`}
        size="sm"
        value={issue.assigneeId ?? "NONE"}
        disabled={pending}
        placeholder="Asignar a…"
        onChange={(v) => {
          if (!v || v === (issue.assigneeId ?? "NONE")) return;
          start(async () => {
            const r = await assignIssueAction(project.key, issue.key, v === "NONE" ? null : v);
            if (r.ok) notify.ok(v === "NONE" ? `${issue.key} sin responsable` : `${issue.key} asignada a ${members.find((m) => m.id === v)?.name ?? "su responsable"}`);
            else notify.error("No se pudo asignar", r.error);
          });
        }}
        options={[
          { value: "NONE", label: compact ? "Asignar a…" : "Sin responsable" },
          ...members.map((m) => ({ value: m.id, label: m.name, leading: <Avatar member={m} size="xs" /> })),
        ]}
      />
    </div>
  );
}

/** Mover la tarjeta a otra columna (al final). Las puertas se cierran con «Aprobar», no moviéndola. */
function MoveSelect({ issue, project }: { issue: Issue; project: Project }) {
  const [pending, start] = useTransition();
  return (
    <SelectInput
      ariaLabel={`Paso de ${issue.key}`}
      size="sm"
      value={issue.status}
      disabled={pending}
      onChange={(v) => {
        if (!v || v === issue.status) return;
        start(async () => {
          const r = await moveIssueAction(project.key, issue.key, v, 2_147_483_647);
          if (r.ok) notify.ok(`${issue.key} → ${columnName(project, v)}`);
          else notify.error("No se pudo mover", r.error);
        });
      }}
      options={project.columns.map((c) => ({ value: c.key, label: c.name }))}
    />
  );
}

/** Comentar; si un agente preguntó, la respuesta vuelve a él para que siga trabajando. */
function Reply({ issue, project, askedBy }: { issue: Issue; project: Project; askedBy: Member | undefined }) {
  const [text, setText] = useState("");
  const [giveBack, setGiveBack] = useState(true);
  const [pending, start] = useTransition();
  const send = () =>
    start(async () => {
      const r = await addCommentAction(issue.key, text.trim());
      if (!r.ok) {
        notify.error("No se pudo comentar", r.error);
        return;
      }
      if (askedBy && giveBack) {
        const a = await assignIssueAction(project.key, issue.key, askedBy.id);
        if (!a.ok) {
          notify.error("Se comentó, pero no se pudo devolver al agente", a.error);
          return;
        }
        notify.ok(`Respondido: ${askedBy.name} sigue con ${issue.key}`);
      } else notify.ok("Comentario agregado");
      setText("");
    });
  return (
    <form
      className="flex flex-col gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (text.trim()) send();
      }}
    >
      <TextArea
        rows={3}
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder={askedBy ? `Responde a ${askedBy.name}…` : "Escribe un comentario…"}
        aria-label="Comentario"
      />
      <div className="flex flex-wrap items-center justify-between gap-2">
        {askedBy ? (
          <label className="flex items-center gap-1.5 text-xs text-fg-muted">
            <input type="checkbox" checked={giveBack} onChange={(e) => setGiveBack(e.target.checked)} />
            Devolvérsela a {askedBy.name}
          </label>
        ) : (
          <span />
        )}
        <Button type="submit" size="sm" tone="primary" icon={MessageSquare} loading={pending} disabled={!text.trim()}>
          {askedBy && giveBack ? "Responder" : "Comentar"}
        </Button>
      </div>
    </form>
  );
}

function columnName(project: Project, status: string): string {
  return project.columns.find((c) => c.key === status)?.name ?? status;
}
