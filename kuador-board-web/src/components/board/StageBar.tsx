"use client";

import { ArrowRight, Check, Hand, Lock, RotateCcw, SquareTerminal, Undo2 } from "lucide-react";
import { useState, useTransition } from "react";

import { deliverIssueAction, reviewIssueAction, superviseIssueAction, takeIssueAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { TextArea } from "@/components/ui/Field";
import { useAccess } from "@/components/shell/AccessContext";
import { Badge } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { Issue, Me, Member, Project } from "@/lib/types";

import { nextColumn, roleList, stageOf } from "./stages";

/**
 * Estado de la etapa del issue y lo que la persona (o el agente) puede hacer: tomarlo, entregarlo
 * a la siguiente etapa o, en una puerta, aprobarlo o rechazarlo con un motivo.
 */
export function StageBar({ issue, project, me, members, roleName }: { issue: Issue; project: Project; me: Me; members: Member[]; roleName: (key: string) => string }) {
  const stage = stageOf(project, issue.status);
  const column = project.columns.find((c) => c.key === issue.status);
  const next = nextColumn(project, issue.status);
  const assignee = members.find((m) => m.id === issue.assigneeId);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const [pending, start] = useTransition();
  const { canEdit } = useAccess();
  if (!stage && issue.reworkCount === 0 && !issue.supervisorId && assignee?.kind !== "AGENT") return null;

  const isHuman = me.kind === "HUMAN";
  const canApprove = !!stage?.gate && isHuman && (stage.approvers.length === 0 || stage.approvers.includes(me.role));
  const covers = !!stage && stage.roles.length > 0 && stage.roles.includes(me.role);
  // Quien solo mira no toma ni entrega: como mucho aprueba o rechaza en su puerta.
  const canTake = canEdit && !issue.assigneeId && (stage?.gate ? covers || canApprove : !stage || stage.roles.length === 0 || covers);
  const mine = issue.assigneeId === me.id;
  // Trabajo para agentes: asignado a uno, o sin tomar en una etapa que cubre algún agente del equipo.
  const agentRoles = new Set(members.filter((m) => m.kind === "AGENT").map((m) => m.role));
  const forAgents = assignee ? assignee.kind === "AGENT" : !!stage && stage.roles.some((r) => agentRoles.has(r));
  const supervisor = members.find((m) => m.id === issue.supervisorId);

  const act = (work: () => Promise<{ ok: boolean; error?: string }>, done: string) =>
    start(async () => {
      const r = await work();
      if (r.ok) notify.ok(done);
      else notify.error("No se pudo completar", r.error);
    });

  return (
    <div className="flex flex-col gap-3 border-b border-line bg-sunken px-5 py-3">
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

      {forAgents && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-xs text-fg-muted">
          <SquareTerminal className="size-3.5 text-fg-faint" aria-hidden />
          {supervisor ? (
            <span>
              {supervisor.id === me.id ? (
                "Lo atienden tus agentes, en tu Claude Code."
              ) : (
                <>
                  Lo atienden los agentes de <span className="font-medium text-fg-strong">{supervisor.name}</span>, en su Claude Code.
                </>
              )}
            </span>
          ) : (
            <span>Espera a un agente, pero nadie lo supervisa todavía.</span>
          )}
          {canEdit && isHuman && supervisor?.id !== me.id && (
            <Button size="sm" tone="ghost" loading={pending} onClick={() => act(() => superviseIssueAction(project.key, issue.key), `Tus agentes atenderán ${issue.key}`)}>
              Que lo atiendan mis agentes
            </Button>
          )}
        </div>
      )}

      {stage && !rejecting && (
        <div className="flex flex-wrap items-center gap-2">
          {canTake && (
            <Button size="sm" tone={canApprove ? "secondary" : "primary"} icon={Hand} loading={pending} onClick={() => act(() => takeIssueAction(project.key, issue.key), `Tomaste ${issue.key}`)}>
              Tomar
            </Button>
          )}
          {canEdit && !stage.gate && (mine || (isHuman && !!issue.assigneeId)) && next && (
            <Button size="sm" tone="primary" icon={ArrowRight} loading={pending} onClick={() => act(() => deliverIssueAction(project.key, issue.key, null), `${issue.key} pasó a ${next.name}`)}>
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
