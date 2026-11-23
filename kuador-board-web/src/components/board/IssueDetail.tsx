"use client";

import Link from "next/link";
import { Ellipsis, FileText, Link2, Trash } from "lucide-react";
import { useEffect, useState, useTransition } from "react";

import {
  addCommentAction,
  deleteCommentAction,
  editCommentAction,
  deleteIssueAction,
  listActivityAction,
  listAgentRunsAction,
  listCommentsAction,
  listIssueDocsAction,
  listMethodologiesAction,
  updateIssueAction,
} from "@/app/actions";
import { ActivityItem } from "@/components/activity/ActivityItem";
import { WorkingLine } from "@/components/agents/WorkingLine";
import { AgentRunItem, usd } from "@/components/agents/AgentRunItem";
import { CreateDocButton } from "@/components/docs/CreateDocButton";
import { Markdown } from "@/components/docs/Markdown";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { useAccess } from "@/components/shell/AccessContext";
import { DropdownMenu } from "@/components/ui/DropdownMenu";
import { TextArea } from "@/components/ui/Field";
import { TypeIcon } from "@/components/ui/IssueIcons";
import { SidePanel } from "@/components/ui/SidePanel";
import { Tabs } from "@/components/ui/Tabs";
import { notify } from "@/components/ui/Toaster";
import { useLiveVersion } from "@/components/live/LiveUpdates";
import { parentLabel, documentsOf } from "@/lib/labels";
import type { Activity, AgentRun, Comment, DocumentMeta, Issue, Me, Member, Methodology, Project, AgentMode } from "@/lib/types";

import { EpicPlan } from "./EpicPlan";
import { IssueAttachments } from "./IssueAttachments";
import { IssueForm, valuesOf } from "./IssueForm";
import { quickActions, StageBar, type QuickAction } from "./StageBar";
import { decisionsChanged } from "@/components/shell/useDecisionsCount";

const dateFormat = new Intl.DateTimeFormat("es-EC", { dateStyle: "medium", timeStyle: "short" });

/** Detalle de un issue en el panel lateral: se edita en el sitio y se guarda con un botón. */
export function IssueDetail({
  issue,
  project,
  members,
  epics,
  workingAgent,
  me,
  roleName,
  onOpenIssue,
  onClose,
  modes = {},
  lastRun,
  everyone,
}: {
  /**
   * Todos los miembros del espacio, para mostrar nombres: un comentario puede ser de un agente que no está en el equipo
   * de este tablero (y no por eso es un «miembro eliminado»).
   */
  everyone?: Member[];
  /** Dónde trabaja cada agente del equipo. */
  modes?: Record<string, AgentMode>;
  /** Última ejecución de un agente en este issue. */
  lastRun?: AgentRun;
  issue: Issue;
  project: Project;
  members: Member[];
  epics: Issue[];
  /** Agente que está trabajando este issue ahora, si hay uno. */
  workingAgent?: Member;
  me: Me;
  roleName: (key: string) => string;
  /** Abre otro issue en el panel (p. ej. una tarea del reparto). */
  onOpenIssue?: (key: string) => void;
  onClose: () => void;
}) {
  const known = everyone ?? members;
  const byId = new Map(known.map((m) => [m.id, m]));
  const { canEdit } = useAccess();
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const reporter = issue.reporterId ? byId.get(issue.reporterId) : undefined;

  return (
    <SidePanel
      open
      onClose={onClose}
      title={
        <span className="flex items-center gap-2">
          <TypeIcon type={issue.type} projectType={project} />
          <span className="tabular font-mono text-xs whitespace-nowrap text-fg-muted">{issue.key}</span>
          {issue.parentKey && (
            <span className="hidden truncate text-xs font-normal text-fg-faint sm:inline">
              en {parentLabel(project).toLowerCase()} {issue.parentKey}
            </span>
          )}
          {workingAgent && <WorkingLine agent={workingAgent} className="ml-2 font-normal whitespace-nowrap" />}
        </span>
      }
      headerExtra={
        <DropdownMenu
          trigger={<Button tone="ghost" size="sm" icon={Ellipsis} iconOnly label="Más acciones" />}
          items={[
            {
              label: "Copiar enlace",
              icon: Link2,
              onSelect: () => {
                void navigator.clipboard.writeText(`${window.location.origin}/projects/${project.key}?issue=${issue.key}`);
                notify.ok("Enlace copiado");
              },
            },
            ...(canEdit ? (["separator", { label: "Eliminar issue", icon: Trash, danger: true, onSelect: () => setConfirming(true) }] as const) : []),
          ]}
        />
      }
    >
      <StageBar issue={issue} project={project} me={me} members={members} roleName={roleName} modes={modes} lastRun={lastRun} workingAgent={workingAgent} />
      {issue.type === "EPIC" && <EpicPlan epic={issue} project={project} roleName={roleName} onOpen={onOpenIssue} />}
      <IssueForm
        project={project}
        initial={valuesOf(issue)}
        members={members}
        epics={epics}
        selfKey={issue.key}
        layout="detail"
        readOnly={!canEdit}
        submitLabel="Guardar cambios"
        pending={pending}
        error={error}
        onSubmit={({ status, ...input }) =>
          startTransition(async () => {
            const result = await updateIssueAction(issue, input, status);
            if (result.ok) {
              setError(null);
              notify.ok(`${issue.key} guardado`);
            } else setError(result.error);
          })
        }
        meta={
          <dl className="mt-2 grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-2 border-t border-line pt-3.5 text-xs">
            <dt className="text-fg-faint">Reportado por</dt>
            <dd className="flex min-w-0 items-center gap-1.5 text-fg">
              {reporter ? (
                <>
                  <Avatar member={reporter} size="xs" /> <span className="truncate">{reporter.name}</span>
                </>
              ) : (
                "—"
              )}
            </dd>
            <dt className="text-fg-faint">Creado</dt>
            <dd className="text-fg">{dateFormat.format(new Date(issue.createdAt))}</dd>
            <dt className="text-fg-faint">Actualizado</dt>
            <dd className="text-fg">{dateFormat.format(new Date(issue.updatedAt))}</dd>
          </dl>
        }
        below={<IssueTabs issueKey={issue.key} members={known} project={project} actions={quickActions({ issue, project, me, canEdit, workingAgent })} />}
      />
      <ConfirmDialog
        open={confirming}
        onCancel={() => setConfirming(false)}
        title={`¿Eliminar ${issue.key}?`}
        message={
          <>
            Se borra «{issue.title}» con sus comentarios. No se puede deshacer.
          </>
        }
        confirmLabel="Eliminar issue"
        pending={pending}
        onConfirm={() =>
          startTransition(async () => {
            const result = await deleteIssueAction(issue.projectKey, issue.key);
            setConfirming(false);
            if (result.ok) {
              notify.ok(`${issue.key} eliminado`);
              onClose();
            } else setError(result.error);
          })
        }
      />
    </SidePanel>
  );
}

function IssueTabs({ issueKey, members, project, actions }: { issueKey: string; members: Member[]; project: Project; actions: QuickAction[] }) {
  const [tab, setTab] = useState<"comments" | "files" | "history" | "docs">("comments");
  // Documentos en tableros que los usan y ya eligieron su manual.
  const withDocs = project.capabilities.includes("DOCUMENTS") && project.methodology != null;
  return (
    <div className="mt-2">
      <Tabs
        ariaLabel="Detalle del issue"
        value={tab}
        onChange={setTab}
        items={[
          { value: "comments", label: "Comentarios", content: <Comments issueKey={issueKey} members={members} actions={actions} /> },
          { value: "files", label: "Adjuntos", content: <IssueAttachments issueKey={issueKey} members={members} /> },
          { value: "history", label: "Historial", content: <IssueHistory issueKey={issueKey} members={members} project={project} /> },
          ...(withDocs ? [{ value: "docs" as const, label: "Documentos", content: <IssueDocs issueKey={issueKey} project={project} /> }] : []),
        ]}
      />
    </div>
  );
}

function IssueDocs({ issueKey, project }: { issueKey: string; project: Project }) {
  const live = useLiveVersion(issueKey);
  const [docs, setDocs] = useState<DocumentMeta[] | null>(null);
  const [guide, setGuide] = useState<Methodology | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    Promise.all([listIssueDocsAction(issueKey), fetchGuide(project)]).then(([result, g]) => {
      if (!active) return;
      if (result.ok) setDocs(result.data);
      else setError(result.error);
      setGuide(g);
    });
    return () => {
      active = false;
    };
  }, [issueKey, project, live]);

  if (error) return <p className="text-xs text-danger">{error}</p>;
  if (!docs || !guide) return <p className="text-xs text-fg-faint">Cargando…</p>;
  const missing = documentsOf(guide, "ISSUE").filter((r) => !docs.some((d) => d.kind === r.kind));
  return (
    <div className="flex flex-col gap-3">
      {docs.length === 0 && (
        <p className="text-xs text-fg-faint">Este issue aún no tiene documentos.</p>
      )}
      <ul className="flex flex-col gap-2">
        {docs.map((d) => (
          <li key={d.id}>
            <Link
              href={`/projects/${project.key}/docs/${d.id}`}
              className="flex items-center gap-2 rounded-md border border-line-strong px-3 py-2 text-[13px] hover:border-line-hover hover:bg-hover"
            >
              <FileText className="size-3.5 shrink-0 text-fg-faint" aria-hidden />
              <span className="min-w-0 flex-1 truncate text-fg-strong">{d.title}</span>
              <span className="tabular font-mono text-[11px] text-fg-faint">v{d.currentVersion}</span>
            </Link>
          </li>
        ))}
      </ul>
      {missing.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {missing.map((r) => (
            <CreateDocButton key={r.kind} projectKey={project.key} kind={r.kind} issueKey={issueKey} label={r.label} compact />
          ))}
        </div>
      )}
    </div>
  );
}

let guides: Promise<Methodology[]> | null = null;

/** El catálogo de metodologías no cambia durante la sesión: se pide una vez (y se reintenta si falló). */
async function fetchGuide(project: Project): Promise<Methodology | null> {
  guides ??= listMethodologiesAction().then((r) => {
    if (!r.ok) {
      guides = null;
      return [];
    }
    return r.data;
  });
  return (await guides).find((m) => m.key === project.methodology) ?? null;
}

function IssueHistory({ issueKey, members, project }: { issueKey: string; members: Member[]; project: Project }) {
  const live = useLiveVersion(issueKey);
  const byId = new Map(members.map((m) => [m.id, m]));
  const [entries, setEntries] = useState<Activity[] | null>(null);
  const [runs, setRuns] = useState<AgentRun[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    listActivityAction(issueKey).then((result) => {
      if (!active) return;
      if (result.ok) setEntries(result.data);
      else setError(result.error);
    });
    listAgentRunsAction(issueKey).then((result) => {
      if (active && result.ok) setRuns(result.data);
    });
    return () => {
      active = false;
    };
  }, [issueKey, live]);

  if (error) return <p className="text-xs text-danger">{error}</p>;
  if (!entries) return <p className="text-xs text-fg-faint">Cargando…</p>;
  return (
    <div className="flex flex-col gap-4">
      {runs.length > 0 && (
        <section aria-label="Ejecuciones de agentes" className="flex flex-col gap-1.5">
          <h4 className="flex flex-wrap items-baseline justify-between gap-2 text-xs font-medium text-fg-muted">
            Ejecuciones de agentes
            {runs.some((r) => r.costUsd != null) && (
              <span className="tabular font-normal text-fg-faint" title="Lo que costó la IA del servidor en esta tarjeta (las últimas ejecuciones).">
                IA en esta tarjeta: {usd(runs.reduce((t, r) => t + (r.costUsd ?? 0), 0))}
              </span>
            )}
          </h4>
          <ul className="flex flex-col gap-1.5">
            {runs.map((r) => (
              <AgentRunItem key={r.id} run={r} agentName={byId.get(r.agentId)?.name} />
            ))}
          </ul>
        </section>
      )}
      {entries.length === 0 ? (
        <p className="text-xs text-fg-faint">Sin actividad registrada.</p>
      ) : (
        <ol className="flex flex-col gap-3">
          {entries.map((a) => (
            <ActivityItem key={a.id} activity={a} members={byId} project={project} />
          ))}
        </ol>
      )}
    </div>
  );
}

function Comments({ issueKey, members, actions = [] }: { issueKey: string; members: Member[]; actions?: QuickAction[] }) {
  const live = useLiveVersion(issueKey);
  const byId = new Map(members.map((m) => [m.id, m]));
  const [comments, setComments] = useState<Comment[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  function send() {
    if (!body.trim() || pending) return;
    startTransition(async () => {
      const result = await addCommentAction(issueKey, body);
      if (result.ok) {
        setComments((prev) => [...(prev ?? []), result.data]);
        setBody("");
        setError(null);
      } else {
        setError(result.error);
      }
    });
  }

  /** Aprobar, rechazar o entregar desde aquí: lo escrito en la caja viaja como comentario (o como motivo del rechazo). */
  function runAction(a: QuickAction) {
    if (pending || (a.needsNote && !body.trim())) return;
    startTransition(async () => {
      const result = await a.run(body.trim() || null);
      if (result.ok) {
        setBody("");
        setError(null);
        notify.ok(a.done);
        decisionsChanged();
      } else setError(result.error);
    });
  }

  useEffect(() => {
    let active = true;
    listCommentsAction(issueKey).then((result) => {
      if (!active) return;
      if (result.ok) setComments(result.data);
      else setLoadError(result.error);
    });
    return () => {
      active = false;
    };
  }, [issueKey, live]);

  return (
    <section aria-label="Comentarios" className="flex flex-col gap-3">
      {loadError && <p className="text-xs text-danger">{loadError}</p>}
      {comments === null && !loadError && <p className="text-xs text-fg-faint">Cargando…</p>}
      {comments?.length === 0 && <p className="text-xs text-fg-faint">Aún no hay comentarios.</p>}
      <ol className="flex flex-col gap-3">
        {comments?.map((c) => (
          <CommentItem
            key={c.id}
            issueKey={issueKey}
            comment={c}
            author={byId.get(c.authorId)}
            onChange={(next) => setComments((prev) => (prev ?? []).map((x) => (x.id === next.id ? next : x)))}
            onDelete={() => setComments((prev) => (prev ?? []).filter((x) => x.id !== c.id))}
          />
        ))}
      </ol>
      <div className="flex flex-col gap-2">
        <TextArea
          rows={3}
          value={body}
          onChange={(e) => setBody(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
              e.preventDefault();
              send();
            }
          }}
          placeholder="Escribe un comentario… (Markdown, ⌘/Ctrl + Enter para enviar)"
          aria-label="Nuevo comentario"
        />
        <div className="flex flex-wrap items-center justify-end gap-2">
          {error && <p className="mr-auto text-xs text-danger">{error}</p>}
          <Button size="sm" tone={actions.length > 0 ? "secondary" : "primary"} onClick={send} loading={pending} disabled={!body.trim()}>
            Comentar
          </Button>
          {actions.map((a) => (
            <Button
              key={a.label}
              size="sm"
              tone={a.tone}
              icon={a.icon}
              disabled={pending || (a.needsNote && !body.trim())}
              title={a.needsNote && !body.trim() ? "Escribe arriba el motivo" : body.trim() ? "Lo escrito va como comentario" : undefined}
              onClick={() => runAction(a)}
            >
              {a.label}
            </Button>
          ))}
        </div>
      </div>
    </section>
  );
}

/**
 * Un comentario. Quien lo escribió puede corregirlo (p. ej. acortarlo para que un agente lea menos) o borrarlo; quien
 * administra también puede borrarlo. La API comprueba lo mismo.
 */
function CommentItem({
  issueKey,
  comment: c,
  author,
  onChange,
  onDelete,
}: {
  issueKey: string;
  comment: Comment;
  author: Member | undefined;
  onChange: (c: Comment) => void;
  onDelete: () => void;
}) {
  const { me, canAdminister } = useAccess();
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [draft, setDraft] = useState(c.body);
  const [pending, start] = useTransition();
  const mine = me?.id === c.authorId;

  const save = () =>
    start(async () => {
      const r = await editCommentAction(issueKey, c.id, draft);
      if (r.ok) {
        onChange(r.data);
        setEditing(false);
      } else notify.error("No se pudo guardar el comentario", r.error);
    });
  const remove = () =>
    start(async () => {
      const r = await deleteCommentAction(issueKey, c.id);
      if (r.ok) onDelete();
      else notify.error("No se pudo borrar el comentario", r.error);
    });

  return (
    <li className="group flex gap-2.5">
      <Avatar member={author} size="md" />
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-baseline gap-x-2 text-[13px]">
          <span className="font-medium text-fg-strong">{author?.name ?? "Miembro eliminado"}</span>
          <span className="text-xs text-fg-faint">
            {dateFormat.format(new Date(c.createdAt))}
            {c.editedAt && " · editado"}
          </span>
          {!editing && (mine || canAdminister) && (
            <span className="ml-auto flex gap-1 text-xs opacity-100 sm:opacity-0 sm:group-hover:opacity-100 sm:group-focus-within:opacity-100">
              {mine && (
                <button type="button" className="k-link" onClick={() => (setDraft(c.body), setEditing(true))}>
                  Editar
                </button>
              )}
              {confirming ? (
                <>
                  <span className="text-fg-faint">¿Borrar?</span>
                  <button type="button" className="text-danger underline" disabled={pending} onClick={remove}>
                    Sí
                  </button>
                  <button type="button" className="k-link" onClick={() => setConfirming(false)}>
                    No
                  </button>
                </>
              ) : (
                <button type="button" className="k-link" onClick={() => setConfirming(true)}>
                  Borrar
                </button>
              )}
            </span>
          )}
        </p>
        {editing ? (
          <div className="mt-1 flex flex-col gap-2">
            <TextArea rows={4} value={draft} onChange={(e) => setDraft(e.target.value)} aria-label="Editar comentario" />
            <div className="flex justify-end gap-2">
              <Button size="sm" tone="ghost" onClick={() => setEditing(false)}>
                Cancelar
              </Button>
              <Button size="sm" tone="primary" onClick={save} loading={pending} disabled={!draft.trim() || draft.trim() === c.body}>
                Guardar
              </Button>
            </div>
          </div>
        ) : (
          <div className="mt-0.5">
            <Markdown content={c.body} compact />
          </div>
        )}
      </div>
    </li>
  );
}
