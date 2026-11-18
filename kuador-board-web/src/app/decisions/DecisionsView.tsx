"use client";

import { Check, ExternalLink, Undo2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { reviewIssueAction, reviewPlanAction } from "@/app/actions";
import { PullMerge } from "@/components/board/PullMerge";
import { Button } from "@/components/ui/Button";
import { DictateButton } from "@/components/ui/DictateButton";
import { TextArea } from "@/components/ui/Field";
import { decisionsChanged } from "@/components/shell/useDecisionsCount";
import { notify } from "@/components/ui/Toaster";
import type { Decision } from "@/lib/types";

const GROUPS: { kind: Decision["kind"]; title: string; empty: string }[] = [
  { kind: "HANDED", title: "❓ Te dejaron", empty: "Ningún agente espera tu respuesta." },
  { kind: "GATE", title: "🖋️ Por aprobar", empty: "Nada espera tu aprobación." },
  { kind: "PLAN", title: "🧩 Repartos", empty: "Ningún reparto espera tu visto bueno." },
];

const ago = (iso: string | null) => {
  if (!iso) return "";
  const min = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (min < 1) return "ahora";
  if (min < 60) return `hace ${min} min`;
  const h = Math.round(min / 60);
  return h < 24 ? `hace ${h} h` : `hace ${Math.round(h / 24)} d`;
};

/**
 * «Mis decisiones»: todo lo que espera a quien mira, con la acción a un toque. Pensada para el celular: aprobar, devolver
 * con un motivo (escrito o dictado) o abrir la tarjeta para responder.
 */
export function DecisionsView({ decisions }: { decisions: Decision[] }) {
  if (decisions.length === 0) {
    return (
      <div className="flex flex-col items-center gap-2 px-6 py-16 text-center">
        <p className="text-3xl" aria-hidden>
          ✅
        </p>
        <p className="text-[15px] font-medium text-fg-strong">Nada espera tu decisión</p>
        <p className="max-w-sm text-[13px] text-fg-muted">Los agentes siguen trabajando. Te avisamos cuando algo te necesite.</p>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-6 px-4 py-5 sm:px-6">
      {GROUPS.map((g) => {
        const items = decisions.filter((d) => d.kind === g.kind);
        if (items.length === 0) return null;
        return (
          <section key={g.kind} className="flex flex-col gap-2" aria-label={g.title}>
            <h2 className="text-[13px] font-medium text-fg-strong">
              {g.title} <span className="tabular text-fg-faint">{items.length}</span>
            </h2>
            <ul className="flex flex-col gap-2">
              {items.map((d) => (
                <DecisionCard key={`${d.kind}-${d.issueKey}`} decision={d} />
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

function DecisionCard({ decision: d }: { decision: Decision }) {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [returning, setReturning] = useState(false);
  const [reason, setReason] = useState("");
  const [gone, setGone] = useState(false);
  const href = `/projects/${d.projectKey}?issue=${d.issueKey}`;

  const decide = (approve: boolean) =>
    start(async () => {
      const comment = approve ? null : reason.trim();
      const r =
        d.kind === "PLAN"
          ? await reviewPlanAction(d.projectKey, d.issueKey, approve, comment)
          : await reviewIssueAction(d.projectKey, d.issueKey, approve, comment);
      if (!r.ok) {
        notify.error("No se pudo guardar", r.error);
        return;
      }
      notify.ok(approve ? `${d.issueKey} aprobada` : `${d.issueKey} devuelta`);
      setGone(true);
      decisionsChanged();
      router.refresh();
    });

  if (gone) return null;
  return (
    <li className="flex flex-col gap-3 rounded-lg border border-line bg-surface px-4 py-3">
      <div className="flex flex-col gap-1">
        <p className="text-xs text-fg-faint">
          {d.projectName} · {d.columnName} · <span className="font-mono">{d.issueKey}</span> {d.since && <>· {ago(d.since)}</>}
        </p>
        <Link href={href} className="text-[15px] leading-snug font-medium text-fg-strong hover:underline">
          {d.title}
        </Link>
        {d.detail && (
          <p className="text-[13px] leading-relaxed text-fg-muted">
            {d.from && <span className="font-medium text-fg">{d.from}: </span>}
            {d.detail}
          </p>
        )}
      </div>

      {d.kind === "GATE" && !returning && <PullMerge projectKey={d.projectKey} issueKey={d.issueKey} />}

      {d.kind === "HANDED" ? (
        <Button href={href} tone="primary" icon={ExternalLink} className="self-start">
          Abrir y responder
        </Button>
      ) : returning ? (
        <form
          className="flex flex-col gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            decide(false);
          }}
        >
          <div className="flex items-start gap-2">
            <TextArea
              rows={3}
              autoFocus
              required
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={d.kind === "PLAN" ? "¿Qué hay que cambiar en el reparto?" : "¿Qué hay que corregir?"}
              aria-label="Motivo"
            />
            <DictateButton onText={(t) => setReason((r) => (r ? `${r} ${t}` : t))} />
          </div>
          <div className="flex gap-2">
            <Button tone="ghost" onClick={() => setReturning(false)} disabled={pending}>
              Cancelar
            </Button>
            <Button type="submit" tone="danger" icon={Undo2} loading={pending} disabled={!reason.trim()}>
              {d.kind === "PLAN" ? "Pedir cambios" : "Devolver"}
            </Button>
          </div>
        </form>
      ) : (
        <div className="flex flex-wrap gap-2">
          <Button tone="primary" icon={Check} loading={pending} onClick={() => decide(true)}>
            {d.kind === "PLAN" ? "Aprobar reparto" : "Aprobar"}
          </Button>
          <Button tone="secondary" icon={Undo2} disabled={pending} onClick={() => setReturning(true)}>
            {d.kind === "PLAN" ? "Pedir cambios" : "Devolver"}
          </Button>
          <Button href={href} tone="ghost" icon={ExternalLink}>
            Ver
          </Button>
        </div>
      )}
    </li>
  );
}
