"use client";

import { ArrowRight, Check, CheckCircle2, Hand, Hourglass, Lock, LoaderCircle, RotateCcw, TriangleAlert, Undo2, Zap } from "lucide-react";
import { useState, useTransition } from "react";

import {
  assignIssueAction,
  deliverIssueAction,
  moveIssueAction,
  retryAgentRunAction,
  reviewIssueAction,
  setReviewByAction,
  superviseIssueAction,
  takeIssueAction,
} from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { SelectInput, TextArea } from "@/components/ui/Field";
import { useAccess } from "@/components/shell/AccessContext";
import { Badge } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { AgentMode, AgentRun, Issue, Me, Member, Project } from "@/lib/types";

import { nextStep, type NextStep, type StepAction } from "./nextStep";
import { nextColumn, roleList, stageOf } from "./stages";

/**
 * Estado de la etapa del issue y lo que la persona (o el agente) puede hacer: tomarlo, entregarlo
 * a la siguiente etapa o, en una puerta, aprobarlo o rechazarlo con un motivo.
 */
export function StageBar({
  issue,
  project,
  me,
  members,
  roleName,
  modes = {},
  lastRun,
  workingAgent,
}: {
  issue: Issue;
  project: Project;
  me: Me;
  members: Member[];
  roleName: (key: string) => string;
  /** Dónde trabaja cada agente del equipo (tablero o PC de una persona). */
  modes?: Record<string, AgentMode>;
  /** Última ejecución de un agente en esta tarjeta. */
  lastRun?: AgentRun;
  workingAgent?: Member;
}) {
  const stage = stageOf(project, issue.status);
  const column = project.columns.find((c) => c.key === issue.status);
  const next = nextColumn(project, issue.status);
  const assignee = members.find((m) => m.id === issue.assigneeId);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const [pending, start] = useTransition();
  const { canEdit } = useAccess();

  const isHuman = me.kind === "HUMAN";
  const canApprove = !!stage?.gate && isHuman && (stage.approvers.length === 0 || stage.approvers.includes(me.role));
  const covers = !!stage && stage.roles.length > 0 && stage.roles.includes(me.role);
  // Quien solo mira no toma ni entrega: como mucho aprueba o rechaza en su puerta.
  const canTake = canEdit && !issue.assigneeId && (stage?.gate ? covers || canApprove : !stage || stage.roles.length === 0 || covers);
  const mine = issue.assigneeId === me.id;
  const act = (work: () => Promise<{ ok: boolean; error?: string }>, done: string) =>
    start(async () => {
      const r = await work();
      if (r.ok) notify.ok(done);
      else notify.error("No se pudo completar", r.error);
    });

  const step = nextStep({ issue, project, me, members, modes, lastRun, workingAgent, canEdit, roleName });
  const runStep = (a: StepAction) => {
    switch (a.kind) {
      case "move":
        return act(() => moveIssueAction(project.key, issue.key, a.to.key, 0), `${issue.key} pasó a ${a.to.name}`);
      case "assign":
        return act(() => assignIssueAction(project.key, issue.key, a.memberId), `${issue.key} asignado`);
      case "take":
        return act(() => takeIssueAction(project.key, issue.key), `Tomaste ${issue.key}`);
      case "supervise":
        return act(() => superviseIssueAction(project.key, issue.key), `Tus agentes atenderán ${issue.key}`);
      case "retry":
        return act(() => retryAgentRunAction(a.runId, project.key), "Reintentando");
    }
  };

  return (
    <div className="flex flex-col gap-3 border-b border-line bg-sunken px-5 py-3">
      <NextStepCallout step={step} pending={pending} onAction={runStep} />
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <span className="flex items-center gap-1.5 text-[13px] font-medium text-fg-strong">
          {stage?.gate && <Lock className="size-3.5 text-sol-text" aria-hidden />}
          {column?.name ?? issue.status}
        </span>
        {stage && (
          <span className="text-xs text-fg-muted">
            {stage.gate ? (
              <>
                Puerta · aprueba {roleList(stage.approvers, roleName, "cualquier persona")}
                {stage.roles.length > 0 && <> · prepara {roleList(stage.roles, roleName)}</>}
              </>
            ) : (
              <>La cubre {roleList(stage.roles, roleName, "cualquiera del equipo")}</>
            )}
          </span>
        )}
        {issue.reworkCount > 0 && (
          <Badge tone={issue.reworkCount >= project.maxRework ? "warn" : "neutral"}>
            <RotateCcw className="size-3" aria-hidden /> Volvió atrás {issue.reworkCount} {issue.reworkCount === 1 ? "vez" : "veces"}
          </Badge>
        )}
        <span className="ml-auto text-xs text-fg-muted">
          {assignee ? <>Lo tiene <span className="font-medium text-fg-strong">{assignee.name}</span></> : stage ? "Sin tomar" : null}
        </span>
      </div>
      {canEdit && isHuman && project.stages.some((s) => s.gate) && (
        <ReviewByPicker issue={issue} project={project} disabled={pending} onPick={(v, msg) => act(() => setReviewByAction(project.key, issue.key, v), msg)} />
      )}

      {stage && !rejecting && (
        <div className="flex flex-wrap items-center gap-2">
          {canTake && !step.actions.some((a) => a.kind === "take") && (
            <Button size="sm" tone={canApprove ? "secondary" : "primary"} icon={Hand} loading={pending} onClick={() => act(() => takeIssueAction(project.key, issue.key), `Tomaste ${issue.key}`)}>
              Tomar
            </Button>
          )}
          {canEdit && !stage.gate && !workingAgent && (mine || (isHuman && !!issue.assigneeId)) && next && (
            <Button size="sm" tone={step.actions.length > 0 ? "secondary" : "primary"} icon={ArrowRight} loading={pending} onClick={() => act(() => deliverIssueAction(project.key, issue.key, null), `${issue.key} pasó a ${next.name}`)}>
              Entregar a {next.name}
            </Button>
          )}
          {canApprove && (
            <>
              <Button size="sm" tone="primary" icon={Check} loading={pending} disabled={!next} onClick={() => act(() => reviewIssueAction(project.key, issue.key, true, null), `${issue.key} aprobado`)}>
                Aprobar{next ? ` y pasar a ${next.name}` : ""}
              </Button>
              <Button size="sm" tone="danger" icon={Undo2} disabled={pending} onClick={() => setRejecting(true)}>
                Rechazar
              </Button>
            </>
          )}
          {stage.gate && !isHuman && <p className="text-xs text-fg-muted">Esta puerta la cierra una persona.</p>}
        </div>
      )}

      {rejecting && (
        <form
          className="flex flex-col gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            act(async () => {
              const r = await reviewIssueAction(project.key, issue.key, false, reason);
              if (r.ok) setRejecting(false);
              return r;
            }, `${issue.key} devuelto`);
          }}
        >
          <TextArea rows={3} autoFocus required value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Qué hay que corregir (lo verá quien lo retome)" aria-label="Motivo del rechazo" />
          <div className="flex justify-end gap-2">
            <Button size="sm" tone="ghost" onClick={() => setRejecting(false)}>Cancelar</Button>
            <Button size="sm" tone="danger" type="submit" loading={pending} disabled={!reason.trim()}>
              Rechazar y devolver
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}

const STEP_STYLE: Record<NextStep["tone"], { icon: React.ComponentType<{ className?: string }>; box: string; icon_: string; label: string }> = {
  action: { icon: Zap, box: "border-sol/50 bg-sol/10", icon_: "text-sol-text", label: "Siguiente paso" },
  wait: { icon: Hourglass, box: "border-line-strong bg-surface", icon_: "text-fg-muted", label: "En espera" },
  working: { icon: LoaderCircle, box: "border-info/40 bg-info/10", icon_: "text-info animate-spin [animation-duration:2.4s]", label: "En curso" },
  warn: { icon: TriangleAlert, box: "border-danger/40 bg-danger/10", icon_: "text-danger", label: "Trabado" },
  done: { icon: CheckCircle2, box: "border-ok/40 bg-ok/10", icon_: "text-ok", label: "Listo" },
};

/** Lo que tiene que pasar ahora, siempre visible arriba del detalle (en cualquier tipo de tablero). */
function NextStepCallout({ step, pending, onAction }: { step: NextStep; pending: boolean; onAction: (a: StepAction) => void }) {
  const style = STEP_STYLE[step.tone];
  const Icon = style.icon;
  return (
    <div role="status" className={`flex flex-wrap items-start gap-x-3 gap-y-2 rounded-lg border px-3 py-2.5 ${style.box}`}>
      <Icon className={`mt-0.5 size-4 shrink-0 ${style.icon_}`} aria-hidden />
      {/* Con poco ancho (p. ej. el panel de la oficina) los botones bajan en vez de apretar el texto. */}
      <div className="min-w-0 flex-1 basis-56">
        <p className="text-[11px] font-medium tracking-wide text-fg-muted uppercase">{style.label}</p>
        <p className="text-[13px] font-medium text-fg-strong">{step.title}</p>
        {step.detail && <p className="mt-0.5 text-xs leading-relaxed text-fg-muted">{step.detail}</p>}
      </div>
      {step.actions.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {step.actions.map((a, i) => (
            <Button key={a.label} size="sm" tone={i === 0 ? "primary" : "secondary"} loading={pending && i === 0} disabled={pending} onClick={() => onAction(a)}>
              {a.label}
            </Button>
          ))}
        </div>
      )}
    </div>
  );
}

/** Quién revisa esta tarjeta en las puertas: lo del tablero, siempre una persona, o el agente revisor. */
function ReviewByPicker({
  issue,
  project,
  disabled,
  onPick,
}: {
  issue: Issue;
  project: Project;
  disabled: boolean;
  onPick: (v: "PERSON" | "AGENT" | null, msg: string) => void;
}) {
  const anyAgent = project.stages.some((s) => s.gate && s.review && s.review.mode !== "PERSON");
  return (
    <div className="flex flex-wrap items-center gap-2 text-xs text-fg-muted">
      <span>Revisión en las puertas</span>
      <div className="w-64 max-w-full">
        <SelectInput<"BOARD" | "PERSON" | "AGENT">
          ariaLabel="Revisión en las puertas"
          size="sm"
          value={issue.reviewBy ?? "BOARD"}
          disabled={disabled}
          onChange={(value) => {
            if (!value) return;
            const v = value === "BOARD" ? null : value;
            onPick(v, v === "PERSON" ? "Esta tarjeta la revisa siempre una persona" : v === "AGENT" ? "Esta tarjeta la revisa el agente" : "Revisión según el tablero");
          }}
          options={[
            { value: "BOARD", label: anyAgent ? "Según el tablero (agente revisor)" : "Según el tablero (una persona)" },
            { value: "PERSON", label: "👤 Siempre una persona" },
            { value: "AGENT", label: "🤖 Que la revise el agente" },
          ]}
        />
      </div>
    </div>
  );
}

