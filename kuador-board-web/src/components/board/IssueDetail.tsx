"use client";

import Link from "next/link";
import { Ellipsis, FileText, Link2, Trash } from "lucide-react";
import { useEffect, useState, useTransition } from "react";

import {
  addCommentAction,
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
import { AgentRunItem } from "@/components/agents/AgentRunItem";
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
import { parentLabel, documentsOf } from "@/lib/labels";
import type { Activity, AgentRun, Comment, DocumentMeta, Issue, Me, Member, Methodology, Project } from "@/lib/types";

import { IssueForm, valuesOf } from "./IssueForm";
import { StageBar } from "./StageBar";

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
  onClose,
}: {
  issue: Issue;
  project: Project;
  members: Member[];
  epics: Issue[];
  /** Agente que está trabajando este issue ahora, si hay uno. */
  workingAgent?: Member;
  me: Me;
  roleName: (key: string) => string;
  onClose: () => void;
}) {
  const byId = new Map(members.map((m) => [m.id, m]));
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
      <StageBar issue={issue} project={project} me={me} members={members} roleName={roleName} />
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
        below={<IssueTabs issueKey={issue.key} members={members} project={project} />}
      />
      <ConfirmDialog
        open={confirming}
        onCancel={() => setConfirming(false)}
        title={`¿Eliminar ${issue.key}?`}
        message={
          <>
            Se borra «{issue.title}» con sus comentarios. Esta acción no se puede deshacer; el historial del proyecto conserva que existió.
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

function IssueTabs({ issueKey, members, project }: { issueKey: string; members: Member[]; project: Project }) {
  const [tab, setTab] = useState<"comments" | "history" | "docs">("comments");
  // Documentos solo en proyectos de software con metodología.
  const withDocs = project.kind === "SOFTWARE" && project.methodology != null;
  return (
    <div className="mt-2">
      <Tabs
        ariaLabel="Detalle del issue"
        value={tab}
        onChange={setTab}
        items={[
          { value: "comments", label: "Comentarios", content: <Comments issueKey={issueKey} members={members} /> },
          { value: "history", label: "Historial", content: <IssueHistory issueKey={issueKey} members={members} project={project} /> },
          ...(withDocs ? [{ value: "docs" as const, label: "Documentos", content: <IssueDocs issueKey={issueKey} project={project} /> }] : []),
        ]}
      />
    </div>
  );
}

function IssueDocs({ issueKey, project }: { issueKey: string; project: Project }) {
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
  }, [issueKey, project]);

  if (error) return <p className="text-xs text-danger">{error}</p>;
  if (!docs || !guide) return <p className="text-xs text-fg-faint">Cargando…</p>;
  const missing = documentsOf(guide, "ISSUE").filter((r) => !docs.some((d) => d.kind === r.kind));
  return (
    <div className="flex flex-col gap-3">
      {docs.length === 0 && (
        <p className="text-xs leading-relaxed text-fg-faint">
          {project.methodology === "ODD"
            ? "ODD solo pide seguimiento si la tarea es sustancial; una tarea simple no necesita documentos."
            : "Este issue aún no tiene documentos."}
        </p>
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
  }, [issueKey]);

  if (error) return <p className="text-xs text-danger">{error}</p>;
  if (!entries) return <p className="text-xs text-fg-faint">Cargando…</p>;
  return (
    <div className="flex flex-col gap-4">
      {runs.length > 0 && (
        <section aria-label="Ejecuciones de agentes" className="flex flex-col gap-1.5">
          <h4 className="text-xs font-medium text-fg-muted">Ejecuciones de agentes</h4>
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

function Comments({ issueKey, members }: { issueKey: string; members: Member[] }) {
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
  }, [issueKey]);

  return (
    <section aria-label="Comentarios" className="flex flex-col gap-3">
      {loadError && <p className="text-xs text-danger">{loadError}</p>}
      {comments === null && !loadError && <p className="text-xs text-fg-faint">Cargando…</p>}
      {comments?.length === 0 && <p className="text-xs text-fg-faint">Aún no hay comentarios.</p>}
      <ol className="flex flex-col gap-3">
        {comments?.map((c) => (
          <li key={c.id} className="flex gap-2.5">
            <Avatar member={byId.get(c.authorId)} size="md" />
            <div className="min-w-0 flex-1">
              <p className="text-[13px]">
                <span className="font-medium text-fg-strong">{byId.get(c.authorId)?.name ?? "Miembro eliminado"}</span>{" "}
                <span className="text-xs text-fg-faint">{dateFormat.format(new Date(c.createdAt))}</span>
              </p>
              <div className="mt-0.5">
                <Markdown content={c.body} compact />
              </div>
            </div>
          </li>
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
        <div className="flex items-center justify-end gap-2">
          {error && <p className="mr-auto text-xs text-danger">{error}</p>}
          <Button size="sm" tone="primary" onClick={send} loading={pending} disabled={!body.trim()}>
            Comentar
          </Button>
        </div>
      </div>
    </section>
  );
}
