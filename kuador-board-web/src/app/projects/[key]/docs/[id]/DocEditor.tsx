"use client";

import { Eye, Pencil, RotateCcw } from "lucide-react";
import { clsx } from "clsx";
import { useEffect, useState, useTransition } from "react";

import { getDocVersionAction, listDocVersionsAction, restoreDocAction, saveDocAction } from "@/app/actions";
import { Markdown } from "@/components/docs/Markdown";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { useAccess } from "@/components/shell/AccessContext";
import { TextArea, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { DocumentMeta, DocumentVersionMeta, Member } from "@/lib/types";

const dateFormat = new Intl.DateTimeFormat("es-EC", { dateStyle: "medium", timeStyle: "short" });

export function DocEditor({
  initial,
  initialContent,
  initialVersions,
  members,
}: {
  initial: DocumentMeta;
  initialContent: string;
  initialVersions: DocumentVersionMeta[];
  members: Member[];
}) {
  const byId = new Map(members.map((m) => [m.id, m]));
  const { canEdit } = useAccess();
  const [doc, setDoc] = useState(initial);
  const [saved, setSaved] = useState(initialContent);
  const [content, setContent] = useState(initialContent);
  const [note, setNote] = useState("");
  const [mode, setMode] = useState<"edit" | "preview">("preview");
  const [versions, setVersions] = useState(initialVersions);
  const [viewing, setViewing] = useState<{ version: number; content: string } | null>(null);
  const [pending, startTransition] = useTransition();
  const dirty = content !== saved;

  // Avisa antes de salir con cambios sin guardar.
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const refreshVersions = async (id: string) => {
    const r = await listDocVersionsAction(id);
    if (r.ok) setVersions(r.data);
  };

  const save = () =>
    startTransition(async () => {
      const r = await saveDocAction(doc.id, doc.currentVersion, content, note.trim() || null);
      if (r.ok) {
        const changed = r.data.document.currentVersion !== doc.currentVersion;
        setDoc(r.data.document);
        setSaved(r.data.content);
        setNote("");
        notify.ok(changed ? `Guardado como versión ${r.data.document.currentVersion}` : "Sin cambios que guardar");
        await refreshVersions(doc.id);
      } else {
        notify.error("No se pudo completar", r.error);
      }
    });

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_15rem]">
      <div className="flex min-w-0 flex-col gap-3">
        {viewing ? (
          <div className="flex flex-wrap items-center gap-2 rounded-md bg-info/10 px-3 py-2 text-[13px] text-fg">
            Estás viendo la versión {viewing.version} (solo lectura).
            <Button size="sm" tone="ghost" onClick={() => setViewing(null)} className="ml-auto">
              Volver a la actual
            </Button>
            {canEdit && (
            <Button
              size="sm"
              tone="primary"
              icon={RotateCcw}
              loading={pending}
              onClick={() =>
                startTransition(async () => {
                  const r = await restoreDocAction(doc.id, viewing.version, doc.currentVersion);
                  if (r.ok) {
                    setDoc(r.data.document);
                    setSaved(r.data.content);
                    setContent(r.data.content);
                    setViewing(null);
                    notify.ok(`Versión ${viewing.version} restaurada`, `Quedó como versión ${r.data.document.currentVersion}.`);
                    await refreshVersions(doc.id);
                  } else {
                    notify.error("No se pudo completar", r.error);
                  }
                })
              }
            >
              Restaurar esta versión
            </Button>
            )}
          </div>
        ) : !canEdit ? null : (
          <div className="flex items-center gap-2">
            <div role="radiogroup" aria-label="Modo" className="inline-flex rounded-md border border-line-strong p-0.5">
              {[
                { id: "preview" as const, label: "Vista previa", Icon: Eye },
                { id: "edit" as const, label: "Editar", Icon: Pencil },
              ].map(({ id, label, Icon }) => (
                <button
                  key={id}
                  type="button"
                  role="radio"
                  aria-checked={mode === id}
                  onClick={() => setMode(id)}
                  className={clsx(
                    "flex items-center gap-1.5 rounded px-2.5 py-1 text-[13px]",
                    mode === id ? "bg-hover font-medium text-fg-strong" : "text-fg-muted hover:text-fg-strong",
                  )}
                >
                  <Icon className="size-3.5" aria-hidden /> {label}
                </button>
              ))}
            </div>
            {dirty && <span className="ml-auto text-xs text-warn">Cambios sin guardar</span>}
          </div>
        )}

        {viewing ? (
          <div className="rounded-lg border border-line bg-surface px-8 py-6">
            <Markdown content={viewing.content} />
          </div>
        ) : mode === "edit" ? (
          <TextArea
            className="min-h-[62dvh] font-mono text-[13px] leading-relaxed"
            value={content}
            onChange={(e) => setContent(e.target.value)}
            onKeyDown={(e) => {
              if ((e.metaKey || e.ctrlKey) && e.key === "s") {
                e.preventDefault();
                if (dirty) save();
              }
            }}
            aria-label="Contenido en Markdown"
            spellCheck
          />
        ) : (
          <div className="rounded-lg border border-line bg-surface px-8 py-6">
            <Markdown content={content} />
          </div>
        )}

        {!viewing && canEdit && (
          <div className="flex flex-wrap items-center gap-2">
            <div className="min-w-48 flex-1">
              <TextInput
                size="sm"
                maxLength={200}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="Qué cambió (opcional)"
                aria-label="Nota de la versión"
              />
            </div>
            <Button tone="primary" size="sm" disabled={!dirty} loading={pending} onClick={save}>
              Guardar versión
            </Button>
          </div>
        )}
      </div>

      <aside aria-label="Versiones" className="flex flex-col gap-2">
        <h2 className="text-xs font-medium text-fg-muted">Versiones</h2>
        <ol className="flex flex-col gap-1">
          {versions.map((v) => {
            const current = v.version === doc.currentVersion;
            const active = viewing ? viewing.version === v.version : current;
            return (
              <li key={v.version}>
                <button
                  type="button"
                  onClick={async () => {
                    if (current) return setViewing(null);
                    const r = await getDocVersionAction(doc.id, v.version);
                    if (r.ok) setViewing({ version: v.version, content: r.data });
                    else notify.error("No se pudo completar", r.error);
                  }}
                  className={clsx("w-full rounded-md px-2.5 py-2 text-left text-xs", active ? "bg-hover" : "hover:bg-hover/60")}
                >
                  <span className="tabular font-mono font-medium text-fg-strong">
                    v{v.version}
                    {current && <span className="ml-1.5 font-sans font-normal text-fg-muted">actual</span>}
                  </span>
                  <span className="mt-1 flex items-center gap-1.5 text-fg-muted">
                    <Avatar member={v.authorId ? byId.get(v.authorId) : undefined} size="xs" />
                    {v.authorId ? (byId.get(v.authorId)?.name ?? "Miembro eliminado") : "Sistema"}
                  </span>
                  {v.note && <span className="block text-fg">{v.note}</span>}
                  <time className="block text-[11px] text-fg-faint">{dateFormat.format(new Date(v.createdAt))}</time>
                </button>
              </li>
            );
          })}
        </ol>
      </aside>
    </div>
  );
}
