"use client";

import { Download, FileText, Paperclip, Trash2, Upload } from "lucide-react";
import { clsx } from "clsx";
import { useCallback, useEffect, useRef, useState, useTransition } from "react";

import { deleteAttachmentAction, listAttachmentsAction } from "@/app/actions";
import { useAccess } from "@/components/shell/AccessContext";
import { Button } from "@/components/ui/Button";
import { notify } from "@/components/ui/Toaster";
import type { Attachment, AttachmentListing, Member } from "@/lib/types";

const IMAGE = /^image\/(png|jpeg|gif|webp)$/;

function size(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1).replace(".", ",")} MB`;
}

const when = (iso: string) => new Intl.DateTimeFormat("es-EC", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }).format(new Date(iso));

/** Archivos de la tarjeta: se sueltan o se eligen; las imágenes se ven en miniatura y todo se descarga con la sesión. */
export function IssueAttachments({ issueKey, members }: { issueKey: string; members: Member[] }) {
  const { canEdit } = useAccess();
  const [listing, setListing] = useState<AttachmentListing | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState<string[]>([]);
  const [dragging, setDragging] = useState(false);
  const [pending, start] = useTransition();
  const input = useRef<HTMLInputElement>(null);

  const load = useCallback(() => {
    listAttachmentsAction(issueKey).then((r) => (r.ok ? setListing(r.data) : setError(r.error)));
  }, [issueKey]);
  useEffect(load, [load]);

  const upload = async (files: FileList | File[]) => {
    const list = Array.from(files);
    if (!listing || list.length === 0) return;
    const tooBig = list.find((f) => f.size > listing.maxBytes);
    if (tooBig) {
      notify.error("Archivo muy grande", `${tooBig.name} pesa ${size(tooBig.size)}; el máximo es ${size(listing.maxBytes)}.`);
      return;
    }
    for (const file of list) {
      setUploading((u) => [...u, file.name]);
      const form = new FormData();
      form.append("file", file);
      try {
        const res = await fetch(`/attachments/${encodeURIComponent(issueKey)}`, { method: "POST", body: form });
        if (!res.ok) {
          const problem = (await res.json().catch(() => null)) as { detail?: string } | null;
          notify.error(`No se pudo subir ${file.name}`, problem?.detail ?? (res.status === 413 ? "El archivo es muy grande." : `La API respondió ${res.status}.`));
        }
      } finally {
        setUploading((u) => u.filter((n) => n !== file.name));
      }
    }
    load();
  };

  if (error) return <p className="py-4 text-[13px] text-danger">{error}</p>;
  if (!listing) return <p className="py-4 text-[13px] text-fg-faint">Cargando adjuntos…</p>;
  if (!listing.available) {
    return <p className="py-4 text-[13px] text-fg-muted">Los adjuntos todavía no están habilitados en este servidor.</p>;
  }

  const who = (id: string | null) => members.find((m) => m.id === id)?.name ?? "Alguien";

  return (
    <section aria-label="Adjuntos" className="flex flex-col gap-3 py-3">
      {canEdit && (
        <div
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            void upload(e.dataTransfer.files);
          }}
          className={clsx(
            "flex flex-wrap items-center justify-between gap-3 rounded-md border border-dashed px-4 py-3 text-[13px]",
            dragging ? "border-sol bg-sol/5" : "border-line-strong",
          )}
        >
          <span className="flex items-center gap-2 text-fg-muted">
            <Paperclip className="size-4" aria-hidden />
            Suelta archivos aquí (hasta {size(listing.maxBytes)} cada uno)
          </span>
          <Button size="sm" icon={Upload} loading={uploading.length > 0} onClick={() => input.current?.click()}>
            Elegir archivos
          </Button>
          <input
            ref={input}
            type="file"
            multiple
            className="sr-only"
            tabIndex={-1}
            onChange={(e) => {
              if (e.target.files) void upload(e.target.files);
              e.target.value = "";
            }}
          />
        </div>
      )}

      {uploading.map((name) => (
        <p key={name} className="text-xs text-fg-muted">Subiendo {name}…</p>
      ))}

      {listing.items.length === 0 && uploading.length === 0 ? (
        <p className="text-[13px] text-fg-faint">Sin adjuntos.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
          {listing.items.map((a) => (
            <AttachmentRow
              key={a.id}
              attachment={a}
              author={who(a.uploadedBy)}
              canDelete={canEdit}
              pending={pending}
              onDelete={() =>
                start(async () => {
                  const r = await deleteAttachmentAction(a.id);
                  if (!r.ok) {
                    notify.error("No se pudo borrar", r.error);
                    return;
                  }
                  setListing((l) => (l ? { ...l, items: l.items.filter((x) => x.id !== a.id) } : l));
                })
              }
            />
          ))}
        </ul>
      )}
    </section>
  );
}

function AttachmentRow({ attachment: a, author, canDelete, pending, onDelete }: { attachment: Attachment; author: string; canDelete: boolean; pending: boolean; onDelete: () => void }) {
  const url = `/attachments/file/${a.id}`;
  return (
    <li className="flex items-center gap-3 px-3 py-2.5">
      {IMAGE.test(a.contentType) ? (
        <a href={`${url}?inline=1`} target="_blank" rel="noreferrer" className="shrink-0">
          {/* eslint-disable-next-line @next/next/no-img-element -- miniatura servida por la ruta con sesión */}
          <img src={`${url}?inline=1`} alt="" className="size-10 rounded border border-line object-cover" />
        </a>
      ) : (
        <span className="grid size-10 shrink-0 place-items-center rounded border border-line bg-sunken text-fg-muted">
          <FileText className="size-4" aria-hidden />
        </span>
      )}
      <div className="min-w-0 flex-1">
        <a href={a.contentType === "application/pdf" ? `${url}?inline=1` : url} target={a.contentType === "application/pdf" ? "_blank" : undefined} rel="noreferrer" className="block truncate text-[13px] font-medium text-fg-strong hover:underline">
          {a.fileName}
        </a>
        <p className="truncate text-xs text-fg-faint">
          {size(a.sizeBytes)} · {author} · {when(a.createdAt)}
        </p>
      </div>
      <Button size="sm" tone="ghost" icon={Download} iconOnly label={`Descargar ${a.fileName}`} href={url} download />
      {canDelete && <Button size="sm" tone="ghost" icon={Trash2} iconOnly label={`Borrar ${a.fileName}`} disabled={pending} onClick={onDelete} />}
    </li>
  );
}
