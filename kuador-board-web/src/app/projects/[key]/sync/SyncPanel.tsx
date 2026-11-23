"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowDownToLine, ArrowUpFromLine, ExternalLink, GitCompare, GitPullRequest, RefreshCw } from "lucide-react";
import { useState, useTransition } from "react";

import {
  syncCompareAction,
  syncExportAction,
  syncImportAction,
  syncImportNewAction,
  syncSafeAction,
} from "@/app/actions";
import { lineDiff } from "@/components/sync/lineDiff";
import { Button } from "@/components/ui/Button";
import { SelectInput, TextInput } from "@/components/ui/Field";
import { Badge, Section } from "@/components/ui/Section";
import { SidePanel } from "@/components/ui/SidePanel";
import type { Exported, Issue, SyncComparison, SyncEntry, SyncState, SyncStatus, UntrackedGroup } from "@/lib/types";

const STATE: Record<SyncState, { label: string; tone: "ok" | "sol" | "info" | "danger" | "warn"; hint: string }> = {
  SYNCED: { label: "Sincronizado", tone: "ok", hint: "Igual en ambos lados." },
  BOARD_AHEAD: { label: "Cambió en el tablero", tone: "sol", hint: "Exporta para escribir el archivo." },
  BOARD_ONLY: { label: "Solo en el tablero", tone: "sol", hint: "Exporta para crear el archivo." },
  FILE_AHEAD: { label: "Cambió en el repo", tone: "info", hint: "Importa para guardar una versión nueva." },
  CONFLICT: { label: "Conflicto", tone: "danger", hint: "Cambió en ambos lados: compara y elige." },
  FILE_DELETED: { label: "Borrado en el repo", tone: "warn", hint: "Se borró el archivo. Exporta si quieres recrearlo." },
};

export function SyncPanel({ projectKey, status, issues }: { projectKey: string; status: SyncStatus; issues: Issue[] }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [message, setMessage] = useState<{ ok: boolean; text: string; url?: string | null } | null>(null);
  const [comparing, setComparing] = useState<SyncComparison | null>(null);

  const act = (work: () => Promise<{ ok: true; data?: unknown } | { ok: false; error: string }>, success: string) =>
    startTransition(async () => {
      const r = await work();
      if (r.ok) {
        setMessage({ ok: true, text: success });
        setComparing(null);
        router.refresh();
      } else {
        setMessage({ ok: false, text: r.error });
      }
    });

  /** Exportar: abre o actualiza el PR del tablero en GitHub. */
  const exportDoc = (docId: string, force: boolean) =>
    startTransition(async () => {
      const r = await syncExportAction(projectKey, docId, force);
      if (r.ok) {
        setMessage(exportedMessage(r.data));
        setComparing(null);
        router.refresh();
      } else {
        setMessage({ ok: false, text: r.error });
      }
    });

  const counts = status.entries.reduce<Record<string, number>>((acc, e) => ({ ...acc, [e.state]: (acc[e.state] ?? 0) + 1 }), {});
  const pendingSafe = (counts.BOARD_AHEAD ?? 0) + (counts.BOARD_ONLY ?? 0) + (counts.FILE_AHEAD ?? 0);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3 rounded-lg border border-line bg-surface px-5 py-4">
        <dl className="flex gap-6">
          {[
            { label: "Documentos", value: status.entries.length, tone: "text-fg-strong" },
            { label: "Sincronizados", value: counts.SYNCED ?? 0, tone: "text-ok" },
            { label: "Con cambios", value: pendingSafe, tone: pendingSafe ? "text-fg-strong" : "text-fg-faint" },
            { label: "En conflicto", value: counts.CONFLICT ?? 0, tone: counts.CONFLICT ? "text-danger" : "text-fg-faint" },
          ].map((m) => (
            <div key={m.label}>
              <dt className="text-xs text-fg-muted">{m.label}</dt>
              <dd className={`tabular font-mono text-lg ${m.tone}`}>{m.value}</dd>
            </div>
          ))}
        </dl>
        <p className="min-w-60 flex-1 text-xs leading-relaxed text-fg-muted">Los conflictos nunca se resuelven solos.</p>
        <Button
          tone="primary"
          size="sm"
          icon={RefreshCw}
          loading={pending}
          disabled={pendingSafe === 0}
          onClick={() =>
            startTransition(async () => {
              const r = await syncSafeAction(projectKey);
              if (r.ok) {
                const parts = [`Importados ${r.data.imported}`];
                if (r.data.exported) parts.push(exportedMessage(r.data.exported).text);
                if (r.data.conflicts) parts.push(`${r.data.conflicts} conflicto(s) esperan tu decisión`);
                setMessage({ ok: true, text: parts.join(" · "), url: r.data.exported?.pullRequestUrl });
                router.refresh();
              } else {
                setMessage({ ok: false, text: r.error });
              }
            })
          }
        >
          Sincronizar lo seguro
        </Button>
      </div>

      {status.openPullRequests.length > 0 && (
        <ul className="flex flex-col gap-2">
          {status.openPullRequests.map((pr) => (
            <li key={pr.number} className="flex flex-wrap items-center gap-2 rounded-md border border-line bg-surface px-4 py-2.5 text-[13px] text-fg">
              <GitPullRequest className="size-4 text-info" aria-hidden />
              PR #{pr.number} abierto con {pr.documents} documento(s).
              <a href={pr.url} target="_blank" rel="noopener noreferrer" className="ml-auto inline-flex items-center gap-1 text-info hover:underline">
                Ver en GitHub <ExternalLink className="size-3.5" aria-hidden />
              </a>
            </li>
          ))}
        </ul>
      )}

      {message && (
        <p role="status" className={`flex flex-wrap items-center gap-2 text-[13px] ${message.ok ? "text-ok" : "text-danger"}`}>
          {message.text}
          {message.url && (
            <a href={message.url} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 underline">
              Abrir PR <ExternalLink className="size-3.5" aria-hidden />
            </a>
          )}
        </p>
      )}

      <Section title="En el tablero" flush>
        {status.entries.length === 0 ? (
          <p className="px-5 py-4 text-[13px] text-fg-faint">Aún no hay documentos en el tablero.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-line">
            {status.entries.map((e) => (
              <EntryRow
                key={e.document.id}
                entry={e}
                projectKey={projectKey}
                pending={pending}
                onExport={() => exportDoc(e.document.id, false)}
                onImport={() => act(() => syncImportAction(projectKey, e.document.id), `${e.document.path} importado como versión nueva.`)}
                onCompare={() =>
                  startTransition(async () => {
                    const r = await syncCompareAction(projectKey, e.document.id);
                    if (r.ok) setComparing(r.data);
                    else setMessage({ ok: false, text: r.error });
                  })
                }
              />
            ))}
          </ul>
        )}
      </Section>

      <Section title="Solo en el repositorio" description="Al importarlos se asocian a una tarjeta." flush>
        {status.untracked.length === 0 ? (
          <p className="px-5 py-4 text-[13px] text-fg-faint">No hay documentos del repositorio pendientes de importar.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-line">
            {status.untracked.map((u) => (
              <UntrackedRow
                key={u.group}
                group={u}
                issues={issues}
                existing={status.entries}
                pending={pending}
                onImport={(issueKey, title) => act(() => syncImportNewAction(projectKey, u.group, issueKey, title), `${u.group} importado al tablero.`)}
              />
            ))}
          </ul>
        )}
      </Section>

      {comparing && (
        <CompareModal
          comparison={comparing}
          pending={pending}
          onClose={() => setComparing(null)}
          onKeepBoard={() => exportDoc(comparing.document.id, true)}
          onKeepFile={() => act(() => syncImportAction(projectKey, comparing.document.id, true), "Se conservó la versión del repo como versión nueva.")}
        />
      )}
    </div>
  );
}

function exportedMessage(e: Exported): { ok: true; text: string; url?: string | null } {
  return { ok: true, text: `${e.documents} documento(s) en el PR #${e.pullRequest}`, url: e.pullRequestUrl };
}

function EntryRow({
  entry,
  projectKey,
  pending,
  onExport,
  onImport,
  onCompare,
}: {
  entry: SyncEntry;
  projectKey: string;
  pending: boolean;
  onExport: () => void;
  onImport: () => void;
  onCompare: () => void;
}) {
  const s = STATE[entry.state];
  const d = entry.document;
  // Esta versión ya va en un PR abierto: solo queda revisarlo y fusionarlo en GitHub.
  const inPr = entry.openPr && entry.openPr.version === d.version ? entry.openPr : null;
  const olderPr = entry.openPr && !inPr ? entry.openPr : null;
  return (
    <li className="flex flex-wrap items-center gap-3 px-5 py-2.5">
      <div className="min-w-0 flex-1">
        <Link href={`/projects/${projectKey}/docs/${d.id}`} className="block truncate text-[13px] font-medium text-fg-strong hover:underline">
          {d.title}
        </Link>
        <code className="block truncate font-mono text-[11px] text-fg-faint">
          {d.path} · v{d.version}
        </code>
      </div>
      {inPr ? (
        <a
          href={inPr.url}
          target="_blank"
          rel="noopener noreferrer"
          title="Esta versión espera revisión y fusión en GitHub"
          className="hover:underline"
        >
          <Badge tone="info">
            <GitPullRequest className="size-3" aria-hidden /> En PR #{inPr.number}
          </Badge>
        </a>
      ) : (
        <span title={s.hint}>
          <Badge tone={s.tone}>
            {s.label}
            {olderPr && ` · PR #${olderPr.number} lleva v${olderPr.version}`}
          </Badge>
        </span>
      )}
      <span className="flex gap-1">
        {!inPr && (entry.state === "BOARD_AHEAD" || entry.state === "BOARD_ONLY" || entry.state === "FILE_DELETED") && (
          <Button size="sm" icon={ArrowUpFromLine} disabled={pending} onClick={onExport}>
            {olderPr ? `Agregar al PR #${olderPr.number}` : "Exportar en PR"}
          </Button>
        )}
        {entry.state === "FILE_AHEAD" && (
          <Button size="sm" icon={ArrowDownToLine} disabled={pending} onClick={onImport}>
            Importar
          </Button>
        )}
        {!inPr && entry.fileExists && entry.state !== "SYNCED" && (
          <Button size="sm" tone={entry.state === "CONFLICT" ? "danger" : "ghost"} icon={GitCompare} disabled={pending} onClick={onCompare}>
            {entry.state === "CONFLICT" ? "Resolver" : "Comparar"}
          </Button>
        )}
      </span>
    </li>
  );
}

function UntrackedRow({
  group,
  issues,
  existing,
  pending,
  onImport,
}: {
  group: UntrackedGroup;
  issues: Issue[];
  existing: SyncEntry[];
  pending: boolean;
  onImport: (issueKey: string | null, newIssueTitle: string | null) => void;
}) {
  const [target, setTarget] = useState("new");
  const [title, setTitle] = useState(group.suggestedTitle ?? "");
  // Un issue tiene un solo documento de cada tipo: no se ofrece uno que ya lo tiene.
  const kinds = new Set(group.files.map((f) => f.kind));
  const taken = (issueKey: string) =>
    existing.find((e) => e.document.issueKey === issueKey && kinds.has(e.document.kind))?.document.kind;
  return (
    <li className="flex flex-col gap-2.5 px-5 py-3">
      <div>
        <code className="font-mono text-[13px] text-fg-strong">{group.group}</code>
        <p className="mt-0.5 text-xs text-fg-muted">{group.files.map((f) => f.path.split("/").pop()).join(" · ")}</p>
      </div>
      <form
        className="flex flex-wrap items-center gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (!group.issueScoped) onImport(null, null);
          else if (target === "new") onImport(null, title);
          else onImport(target, null);
        }}
      >
        {group.issueScoped && (
          <>
            <div className="w-64">
              <SelectInput
                size="sm"
                ariaLabel={`Issue para ${group.group}`}
                options={[
                  { value: "new", label: "Crear tarjeta nueva" },
                  ...issues.filter((i) => !taken(i.key)).map((i) => ({ value: i.key, label: `${i.key} · ${i.title}` })),
                ]}
                value={target}
                onChange={(v) => setTarget(v ?? "new")}
              />
            </div>
            {target === "new" && (
              <div className="min-w-56 flex-1">
                <TextInput size="sm" required maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} aria-label="Título de la tarjeta nueva" />
              </div>
            )}
          </>
        )}
        <Button type="submit" size="sm" icon={ArrowDownToLine} disabled={pending}>
          Importar al tablero
        </Button>
      </form>
    </li>
  );
}

function CompareModal({
  comparison,
  pending,
  onClose,
  onKeepBoard,
  onKeepFile,
}: {
  comparison: SyncComparison;
  pending: boolean;
  onClose: () => void;
  onKeepBoard: () => void;
  onKeepFile: () => void;
}) {
  const file = comparison.fileContent ?? "";
  const diff = lineDiff(file, comparison.boardContent);
  const conflict = comparison.state === "CONFLICT";
  return (
    <SidePanel
      open
      onClose={onClose}
      title={<code className="font-mono text-xs">{comparison.document.path}</code>}
      footer={
        conflict ? (
          <div className="flex flex-wrap justify-end gap-2">
            <Button icon={ArrowDownToLine} disabled={pending} onClick={onKeepFile}>
              Quedarme con el repo
            </Button>
            <Button tone="primary" icon={ArrowUpFromLine} loading={pending} onClick={onKeepBoard}>
              Quedarme con el tablero (PR)
            </Button>
          </div>
        ) : undefined
      }
    >
      <div className="flex flex-col gap-4 p-5">
        <p className="text-[13px] leading-relaxed text-fg">
          <Badge tone="info" className="font-mono">− main</Badge> <Badge tone="ok" className="font-mono">+ tablero v{comparison.document.version}</Badge>{" "}
          {conflict
            ? "Cambió en ambos lados. Si eliges el tablero, se abre un PR que reemplaza el archivo."
            : "Diferencias entre el repo y el tablero."}
        </p>
        {diff ? (
          <pre className="overflow-auto rounded-md border border-line bg-sunken p-3 font-mono text-[12px] leading-relaxed">
            {diff.map((line, k) =>
              line.op === "gap" ? (
                <div key={k} className="py-1 text-fg-faint">⋯ {line.count} líneas sin cambios</div>
              ) : (
                <div
                  key={k}
                  className={line.op === "add" ? "bg-ok/12 text-fg-strong" : line.op === "del" ? "bg-info/12 text-info" : "text-fg-muted"}
                >
                  {line.op === "add" ? "+ " : line.op === "del" ? "− " : "  "}
                  {line.text || " "}
                </div>
              ),
            )}
          </pre>
        ) : (
          <div className="grid gap-3 md:grid-cols-2">
            {[["Repo", file], [`Tablero v${comparison.document.version}`, comparison.boardContent]].map(([label, text]) => (
              <div key={label}>
                <p className="mb-1 text-xs text-fg-muted">{label}</p>
                <pre className="max-h-[50dvh] overflow-auto rounded-lg border border-line bg-sunken p-3 font-mono text-[12px] whitespace-pre-wrap">{text}</pre>
              </div>
            ))}
          </div>
        )}
      </div>
    </SidePanel>
  );
}
