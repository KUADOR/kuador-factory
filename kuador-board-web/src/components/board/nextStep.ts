import type { AgentMode, AgentRun, BoardColumn, Issue, Me, Member, Project } from "@/lib/types";

import { canMove, reviewFor, reviewerRole, roleList, stageOf } from "./stages";

/** Una acción que el «Siguiente paso» ofrece con un botón. */
export type StepAction =
  | { kind: "move"; to: BoardColumn; label: string }
  | { kind: "assign"; memberId: string; label: string }
  | { kind: "take"; label: string }
  | { kind: "supervise"; label: string }
  | { kind: "retry"; runId: string; label: string };

/**
 * Qué tiene que pasar ahora con la tarjeta, dicho de forma explícita. {@code tone}: «action» si le toca a quien mira,
 * «wait» si espera a alguien más, «working» si un agente trabaja, «warn» si algo está trabado, «done» si terminó.
 */
export interface NextStep {
  tone: "action" | "wait" | "working" | "warn" | "done";
  title: string;
  detail?: string;
  actions: StepAction[];
}

export interface StepContext {
  issue: Issue;
  project: Project;
  me: Me;
  members: Member[];
  /** Dónde trabaja cada agente del equipo del tablero. */
  modes: Record<string, AgentMode>;
  /** Última ejecución de un agente en esta tarjeta. */
  lastRun?: AgentRun;
  /** Agente que la está trabajando ahora. */
  workingAgent?: Member;
  canEdit: boolean;
  roleName: (key: string) => string;
}

/**
 * El siguiente paso de una tarjeta en cualquier tablero (software, ventas, tareas o uno propio): sale de sus columnas y
 * etapas, de quién la tiene, del equipo de agentes y de lo último que hizo un agente.
 */
export function nextStep(c: StepContext): NextStep {
  const { issue, project, me, members, modes, lastRun, workingAgent, canEdit, roleName } = c;
  const column = project.columns.find((col) => col.key === issue.status);
  const stage = stageOf(project, issue.status);
  const assignee = members.find((m) => m.id === issue.assigneeId);
  const agents = members.filter((m) => m.kind === "AGENT");
  const name = (m?: Member) => m?.name ?? "alguien";
  const mineToDo = issue.assigneeId === me.id;

  if (column?.category === "DONE") return { tone: "done", title: `Terminada en «${column.name}».`, actions: [] };

  if (workingAgent) {
    return { tone: "working", title: `${workingAgent.name} está trabajando en esta tarjeta.`, detail: undefined, actions: [] };
  }

  // Lo último que hizo un agente aquí, si quedó trabado.
  if (lastRun && lastRun.status === "FAILED") {
    const agent = members.find((m) => m.id === lastRun.agentId);
    return {
      tone: "warn",
      title: `${name(agent)} no pudo terminar.`,
      detail: lastRun.error ?? "Revisa el último comentario del agente.",
      actions: canEdit ? [{ kind: "retry", runId: lastRun.id, label: "Reintentar" }] : [],
    };
  }
  const asked = lastRun && lastRun.status === "SUCCEEDED" && /pregunt/.test(lastRun.summary ?? "") && assignee?.kind === "HUMAN";
  if (asked) {
    const agent = members.find((m) => m.id === lastRun!.agentId);
    return {
      tone: mineToDo ? "action" : "wait",
      title: mineToDo ? `${name(agent)} te hizo preguntas.` : `${name(agent)} hizo preguntas a ${assignee!.name}.`,
      detail: "Respóndelas en un comentario y vuelve a asignársela.",
      actions: mineToDo && agent && canEdit ? [{ kind: "assign", memberId: agent.id, label: `Ya respondí: devolver a ${agent.name}` }] : [],
    };
  }

  // Puerta de aprobación.
  if (stage?.gate) {
    const approvers = roleList(stage.approvers, roleName, "cualquier persona del equipo");
    const canApprove = me.kind === "HUMAN" && (stage.approvers.length === 0 || stage.approvers.includes(me.role));
    const mode = reviewFor(stage, issue.reviewBy);
    if (mode !== "PERSON" && assignee?.kind !== "HUMAN") {
      const reviewer = agents.find((a) => a.role === reviewerRole(stage));
      if (!reviewer) {
        return {
          tone: "warn",
          title: "Esta puerta la revisa un agente, pero no hay Agente Revisor en el equipo.",
          detail: "Guarda de nuevo las etapas (Configuración → Etapas) para sumarlo, o apruébala tú.",
          actions: [],
        };
      }
      return {
        tone: "working",
        title: assignee ? `${reviewer.name} la está revisando.` : `${reviewer.name} la revisa en cuanto la tome.`,
        detail:
          mode === "AGENT_ESCALATE"
            ? `Aprueba lo claro, devuelve lo que falla y te deja lo dudoso con su análisis.${canApprove ? " Igual puedes aprobarla o devolverla tú." : ""}`
            : `Aprueba o devuelve; solo pasa a una persona si no puede decidir.${canApprove ? " Igual puedes decidir tú." : ""}`,
        actions: !assignee && canEdit ? [{ kind: "assign", memberId: reviewer.id, label: `Que la revise ${reviewer.name}` }] : [],
      };
    }
    if (mode !== "PERSON" && assignee?.kind === "HUMAN" && canApprove) {
      return { tone: "action", title: "El agente revisor te la dejó: decides tú.", detail: "Su análisis está en los comentarios.", actions: [] };
    }
    return canApprove
      ? { tone: "action", title: "Te toca revisar y aprobar.", detail: undefined, actions: [] }
      : { tone: "wait", title: `Espera la aprobación de ${approvers}.`, detail: assignee ? `Avisado: ${assignee.name}.` : undefined, actions: [] };
  }

  // Etapa de trabajo con roles: quién la cubre.
  if (stage && stage.roles.length > 0) {
    const roles = roleList(stage.roles, roleName);
    if (assignee?.kind === "AGENT") {
      const mode = modes[assignee.id];
      if (mode === "SERVER") return { tone: "working", title: `${assignee.name} la tomará en el tablero.`, detail: "Si tiene dudas, te pregunta aquí.", actions: [] };
      const supervisor = members.find((m) => m.id === issue.supervisorId);
      return {
        tone: supervisor?.id === me.id ? "action" : "wait",
        title: supervisor
          ? supervisor.id === me.id
            ? `${assignee.name} trabaja en tu PC: corre /pendientes en Claude Code o Codex.`
            : `${assignee.name} trabaja en la PC de ${supervisor.name} (con /pendientes).`
          : `${assignee.name} trabaja en la PC de una persona, pero nadie lo supervisa.`,
        detail: supervisor ? undefined : "Quien supervisa la corre con /pendientes desde su cliente de IA.",
        actions: !supervisor && canEdit && me.kind === "HUMAN" ? [{ kind: "supervise", label: "Que lo atiendan mis agentes" }] : [],
      };
    }
    if (assignee) {
      return mineToDo
        ? { tone: "action", title: `Te toca: trabajo de «${column?.name}».`, detail: undefined, actions: [] }
        : { tone: "wait", title: `La tiene ${assignee.name}.`, actions: [] };
    }
    const candidates = agents.filter((a) => stage.roles.includes(a.role));
    const server = candidates.find((a) => modes[a.id] === "SERVER");
    if (server) {
      return {
        tone: "action",
        title: `Asígnala a ${server.name} y empieza solo.`,
        detail: `La etapa la cubre ${roles}.`,
        actions: canEdit ? [{ kind: "assign", memberId: server.id, label: `Asignar a ${server.name}` }] : [],
      };
    }
    if (candidates.length > 0) {
      return {
        tone: "wait",
        title: `Espera a ${candidates[0].name}: lo toma quien corra /pendientes.`,
        detail: `La etapa la cubre ${roles}, que trabaja en la PC de una persona.`,
        actions: canEdit ? [{ kind: "assign", memberId: candidates[0].id, label: `Asignar a ${candidates[0].name}` }] : [],
      };
    }
    return {
      tone: "warn",
      title: `Nadie del equipo cubre «${column?.name}».`,
      detail: `La etapa pide ${roles}. Agrega ese agente en Configuración → Equipo de agentes, o tómala tú.`,
      actions: canEdit ? [{ kind: "take", label: "Tomarla yo" }] : [],
    };
  }

  // Columna sin etapa (Backlog, Prospecto, Pendiente, Contactado…): qué la hace avanzar.
  const ahead = firstStageAhead(project, issue.status);
  if (column?.category === "TODO" && ahead) {
    const st = stageOf(project, ahead.key)!;
    const who = st.gate
      ? `la revisa ${roleList(st.approvers, roleName, "una persona")}`
      : agents.find((a) => st.roles.includes(a.role))
        ? `la toma ${agents.find((a) => st.roles.includes(a.role))!.name}`
        : `la cubre ${roleList(st.roles, roleName)}`;
    return {
      tone: "action",
      title: `Para que arranque, llévala a «${ahead.name}»: ${who}.`,
      detail: `En «${column.name}» la tarjeta espera; nadie la trabaja todavía.`,
      actions: canEdit ? [{ kind: "move", to: ahead, label: `Enviar a ${ahead.name}` }] : [],
    };
  }
  const next = nextOpen(project, issue.status);
  if (!assignee) {
    return {
      tone: "action",
      title: "Asígnala a alguien del equipo.",
      detail: next ? `Quien la tenga la pasa a «${next.name}» cuando avance.` : undefined,
      actions: canEdit && me.kind === "HUMAN" ? [{ kind: "take", label: "Tomarla yo" }] : [],
    };
  }
  return {
    tone: mineToDo ? "action" : "wait",
    title: mineToDo ? "Te toca." : `La tiene ${assignee.name}.`,
    detail: next ? `Cuando avance, pásala a «${next.name}».` : undefined,
    actions: canEdit && next && (mineToDo || me.kind === "HUMAN") ? [{ kind: "move", to: next, label: `Pasar a ${next.name}` }] : [],
  };
}

/** La primera columna con etapa (trabajo de un rol o puerta) a la que se puede llegar desde esta. */
export function firstStageAhead(project: Project, from: string): BoardColumn | undefined {
  const i = project.columns.findIndex((c) => c.key === from);
  return project.columns
    .slice(i + 1)
    .find((c) => c.category !== "DONE" && canMove(project, from, c.key) && !!stageOf(project, c.key) && (stageOf(project, c.key)!.gate || stageOf(project, c.key)!.roles.length > 0));
}

/** La columna siguiente que no está terminada (o la primera terminada si no hay otra). */
function nextOpen(project: Project, from: string): BoardColumn | undefined {
  const i = project.columns.findIndex((c) => c.key === from);
  const after = project.columns.slice(i + 1).filter((c) => canMove(project, from, c.key));
  return after.find((c) => c.category !== "DONE") ?? after[0];
}
