"use client";

import { ArrowRight, Check, Mail, MessageCircle, NotebookPen, Phone } from "lucide-react";
import Link from "next/link";
import { useState, useTransition } from "react";

import { addCommentAction, moveIssueAction, reviewIssueAction } from "@/app/actions";
import { useAccess } from "@/components/shell/AccessContext";
import { Button } from "@/components/ui/Button";
import { DictateButton } from "@/components/ui/DictateButton";
import { TextArea } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { Issue, Project } from "@/lib/types";

const END = 2147483647;

/** Teléfono y correo escritos en la tarjeta (en la descripción o el título): los botones de contacto salen de ahí. */
function contacts(i: Issue): { phone: string | null; email: string | null } {
  const text = `${i.title}\n${i.description ?? ""}`;
  const email = text.match(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/)?.[0] ?? null;
  const raw = text.match(/(?:\+?\d[\d\s().-]{7,}\d)/)?.[0] ?? null;
  const phone = raw ? raw.replace(/[^\d+]/g, "") : null;
  return { phone: phone && phone.replace(/\D/g, "").length >= 8 ? phone : null, email };
}

/**
 * Ventas en movimiento: cada prospecto abierto con su etapa y lo siguiente a hacer, pensado para el celular entre
 * reuniones. Lo terminado (ganado o perdido) no aparece.
 */
export function SalesView({ project, issues }: { project: Project; issues: Issue[] }) {
  const done = new Set(project.columns.filter((c) => c.category === "DONE").map((c) => c.key));
  const order = project.columns.filter((c) => !done.has(c.key));
  const open = issues.filter((i) => !done.has(i.status) && i.type !== "EPIC");
  if (open.length === 0) {
    return <p className="px-6 py-10 text-center text-[13px] text-fg-muted">No hay prospectos abiertos. Captura uno con «+».</p>;
  }
  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-6 px-4 py-5 sm:px-6">
      {order.map((col) => {
        const here = open.filter((i) => i.status === col.key);
        if (here.length === 0) return null;
        return (
          <section key={col.key} className="flex flex-col gap-2" aria-label={col.name}>
            <h2 className="text-[13px] font-medium text-fg-strong">
              {col.name} <span className="tabular text-fg-faint">{here.length}</span>
            </h2>
            <ul className="flex flex-col gap-2">
              {here.map((i) => (
                <Prospect key={i.key} issue={i} project={project} />
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

function Prospect({ issue, project }: { issue: Issue; project: Project }) {
  const { me, canEdit } = useAccess();
  const [pending, start] = useTransition();
  const [noting, setNoting] = useState(false);
  const [note, setNote] = useState("");
  const [gone, setGone] = useState(false);
  const { phone, email } = contacts(issue);
  const idx = project.columns.findIndex((c) => c.key === issue.status);
  const stage = project.stages.find((s) => s.column === issue.status);
  const later = project.columns.slice(idx + 1);
  const next = later.find((c) => c.category !== "DONE");
  // En la última etapa abierta se cierra el trato: ganado o perdido.
  const closing = !next ? later.filter((c) => c.category === "DONE") : [];
  const canApprove = !!stage?.gate && !!me && (stage.approvers.length === 0 || stage.approvers.includes(me.role));

  const act = (work: () => Promise<{ ok: boolean; error?: string }>, done: string, leaves = true) =>
    start(async () => {
      const r = await work();
      if (!r.ok) {
        notify.error("No se pudo guardar", r.error);
        return;
      }
      notify.ok(done);
      if (leaves) setGone(true);
    });

  if (gone) return null;
  return (
    <li className="flex flex-col gap-3 rounded-lg border border-line bg-surface px-4 py-3">
      <div className="flex flex-col gap-0.5">
        <Link href={`/projects/${project.key}?issue=${issue.key}`} className="text-[15px] leading-snug font-medium text-fg-strong hover:underline">
          {issue.title}
        </Link>
        <p className="text-xs text-fg-faint">
          <span className="font-mono">{issue.key}</span>
          {stage?.gate ? " · espera aprobación" : stage && stage.roles.length > 0 ? " · la prepara un agente" : ""}
        </p>
      </div>

      {(phone || email) && (
        <div className="flex flex-wrap gap-2">
          {phone && (
            <>
              <Button size="sm" tone="secondary" icon={Phone} href={`tel:${phone}`}>
                Llamar
              </Button>
              <Button size="sm" tone="secondary" icon={MessageCircle} href={`https://wa.me/${phone.replace(/\D/g, "")}`}>
                WhatsApp
              </Button>
            </>
          )}
          {email && (
            <Button size="sm" tone="secondary" icon={Mail} href={`mailto:${email}`}>
              Correo
            </Button>
          )}
        </div>
      )}

      {noting ? (
        <form
          className="flex flex-col gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            act(() => addCommentAction(issue.key, `📝 Nota de la reunión: ${note.trim()}`), "Nota guardada", false);
            setNote("");
            setNoting(false);
          }}
        >
          <div className="flex items-start gap-2">
            <TextArea rows={3} autoFocus required value={note} onChange={(e) => setNote(e.target.value)} placeholder="Qué se habló, qué pidió, próximo paso…" aria-label="Nota de la reunión" />
            <DictateButton onText={(t) => setNote((x) => (x.trim() ? `${x.trimEnd()} ${t}` : t))} />
          </div>
          <div className="flex gap-2">
            <Button size="sm" tone="ghost" onClick={() => setNoting(false)}>
              Cancelar
            </Button>
            <Button size="sm" type="submit" tone="primary" loading={pending} disabled={!note.trim()}>
              Guardar nota
            </Button>
          </div>
        </form>
      ) : (
        canEdit && (
          <div className="flex flex-wrap gap-2">
            <Button size="sm" tone="ghost" icon={NotebookPen} onClick={() => setNoting(true)}>
              Nota de la reunión
            </Button>
            {stage?.gate ? (
              canApprove && (
                <Button size="sm" tone="primary" icon={Check} loading={pending} onClick={() => act(() => reviewIssueAction(project.key, issue.key, true, null), `${issue.key} aprobada`)}>
                  Aprobar
                </Button>
              )
            ) : next ? (
              <Button size="sm" tone="primary" icon={ArrowRight} loading={pending} onClick={() => act(() => moveIssueAction(project.key, issue.key, next.key, END), `${issue.key} pasó a ${next.name}`)}>
                {next.name}
              </Button>
            ) : (
              closing.map((c) => (
                <Button key={c.key} size="sm" tone={c.key === closing[0].key ? "primary" : "secondary"} loading={pending} onClick={() => act(() => moveIssueAction(project.key, issue.key, c.key, END), `${issue.key}: ${c.name}`)}>
                  {c.name}
                </Button>
              ))
            )}
          </div>
        )
      )}
    </li>
  );
}
