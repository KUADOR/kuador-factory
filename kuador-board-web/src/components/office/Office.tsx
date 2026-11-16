"use client";

import { clsx } from "clsx";
import { Building2, List, Maximize2, Stamp, Volume2, VolumeX } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";

import { Avatar } from "@/components/ui/Avatar";
import { SidePanel } from "@/components/ui/SidePanel";
import type { AgentMode, AgentRun, Issue, Me, Member, Project } from "@/lib/types";

import { Desk, type DeskState } from "./Desk";

/** Dónde está una tarjeta en la oficina: en un escritorio, en la sala de revisión, en la bandeja o en el archivo. */
type Spot = `desk:${string}` | "review" | "inbox" | "done";

/** Lo que muestra cada puesto. */
export interface Seat {
  member: Member;
  state: DeskState;
  /** Tarjeta en la pantalla (trabajando o la primera de su cola). */
  current: Issue | null;
  queue: Issue[];
  approvals: Issue[];
  mode: AgentMode | null;
  note: string;
}

const RECENT_MS = 3 * 60 * 60 * 1000;

/**
 * La oficina en vivo: cada persona y agente del tablero en su escritorio. Se actualiza con los avisos en vivo del
 * proyecto (el layout recarga los datos) y, cuando una tarjeta cambia de manos, un papel vuela de un puesto al otro.
 */
export function Office({
  project,
  issues,
  members,
  runs,
  me,
  roleNames,
  modes,
  now,
}: {
  /** Hora de los datos (del servidor): para saber si un error o una pregunta es reciente. */
  now: number;
  project: Project;
  issues: Issue[];
  members: Member[];
  runs: AgentRun[];
  me: Me;
  roleNames: Record<string, string>;
  modes: Record<string, AgentMode>;
}) {
  const [view, setView] = useState<"office" | "list">("office");
  const [openId, setOpenId] = useState<string | null>(null);
  const floor = useRef<HTMLDivElement>(null);
  const wrapper = useRef<HTMLDivElement>(null);
  const sound = useSyncExternalStore(subscribeSound, () => readSound(), () => false);
  const hidden = useSyncExternalStore(subscribeVisibility, () => document.hidden, () => false);

  const gates = new Set(project.stages.filter((s) => s.gate).map((s) => s.column));
  const done = new Set(project.columns.filter((c) => c.category === "DONE").map((c) => c.key));
  const memberIds = new Set(members.map((m) => m.id));
  const spotOf = (i: Issue): Spot =>
    gates.has(i.status) ? "review" : done.has(i.status) ? "done" : i.assigneeId && memberIds.has(i.assigneeId) ? `desk:${i.assigneeId}` : "inbox";

  const seats = members
    .map((m) => seat(m, issues, runs, gates, done, modes[m.id] ?? null, now))
    .sort((a, b) => Number(a.member.kind === "AGENT") - Number(b.member.kind === "AGENT") || a.member.name.localeCompare(b.member.name));
  const inReview = issues.filter((i) => gates.has(i.status));
  const inbox = issues.filter((i) => spotOf(i) === "inbox");
  const archived = issues.filter((i) => done.has(i.status)).length;
  const open = seats.find((s) => s.member.id === openId) ?? null;

  // Papeles que vuelan cuando una tarjeta cambia de lugar. Se dibujan fuera de React (son efímeros).
  const spots = useRef<Map<string, Spot> | null>(null);
  const issuesKey = issues.map((i) => `${i.key}:${i.status}:${i.assigneeId}`).join("|");
  useEffect(() => {
    const next = new Map(issues.map((i) => [i.key, spotOf(i)] as const));
    const before = spots.current;
    spots.current = next;
    const container = floor.current;
    if (!before || !container || view !== "office" || matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    let flew = false;
    for (const [key, to] of next) {
      const from = before.get(key);
      if (!from || from === to) continue;
      flew = fly(container, key, from, to) || flew;
    }
    if (flew && readSound()) chime();
    // spotOf depende de los datos que ya resume issuesKey.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [issuesKey, view]);

  return (
    <div ref={wrapper} className={clsx("office flex min-h-0 flex-1 flex-col bg-page", hidden && "office-paused")}>
      <div className="flex flex-wrap items-center gap-2 px-4 py-3 sm:px-6">
        <p className="min-w-0 flex-1 text-[13px] text-fg-muted">
          {seats.filter((s) => s.state === "working").length} trabajando · {seats.filter((s) => s.state === "asking" || s.state === "review").length} esperan a una
          persona · {inbox.length} en la bandeja
        </p>
        <div role="group" aria-label="Vista" className="flex rounded-md border border-line">
          <ToolButton active={view === "office"} onClick={() => setView("office")} icon={Building2} label="Oficina" />
          <ToolButton active={view === "list"} onClick={() => setView("list")} icon={List} label="Lista" />
        </div>
        <ToolButton onClick={() => writeSound(!sound)} icon={sound ? Volume2 : VolumeX} label={sound ? "Sonido activado" : "Sin sonido"} iconOnly />
        <ToolButton
          onClick={() => (document.fullscreenElement ? document.exitFullscreen() : wrapper.current?.requestFullscreen())}
          icon={Maximize2}
          label="Pantalla completa (para un TV)"
          iconOnly
        />
      </div>

      {view === "list" ? (
        <OfficeList seats={seats} roleNames={roleNames} projectKey={project.key} meId={me.id} />
      ) : (
        <div className="min-h-0 flex-1 overflow-auto px-4 pb-6 sm:px-6">
          <div ref={floor} className="office-floor relative rounded-xl border border-line p-3 sm:min-w-[40rem] sm:p-5">
            <div className="mb-5 grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_18rem]">
              <Whiteboard project={project} issues={issues} />
              <div className="grid grid-cols-2 gap-3">
                <div data-spot="inbox" className="office-zone flex flex-col items-center justify-center gap-1 rounded-lg p-3 text-center">
                  <span aria-hidden className="office-tray" />
                  <span className="text-xs font-medium text-fg-strong">Bandeja</span>
                  <span className="tabular text-[11px] text-fg-muted">{inbox.length} sin asignar</span>
                </div>
                <div data-spot="done" className="office-zone flex flex-col items-center justify-center gap-1 rounded-lg p-3 text-center">
                  <span aria-hidden className="office-shelf" />
                  <span className="text-xs font-medium text-fg-strong">Archivo</span>
                  <span className="tabular text-[11px] text-fg-muted">{archived} terminadas</span>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_16rem]">
              <ul aria-label="Puestos de trabajo" className="grid grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))] gap-x-3 gap-y-5 sm:gap-x-4 sm:gap-y-6">
                {seats.map((s) => (
                  <li key={s.member.id} data-spot={`desk:${s.member.id}`}>
                    <Desk seat={s} me={s.member.id === me.id} roleName={roleNames[s.member.role] ?? s.member.role} onOpen={() => setOpenId(s.member.id)} />
                  </li>
                ))}
              </ul>
              {gates.size > 0 && (
                <section data-spot="review" aria-label="Sala de revisión" className="office-room self-start rounded-xl p-4">
                  <h2 className="flex items-center gap-1.5 text-xs font-semibold text-fg-strong">
                    <Stamp className="size-3.5 text-sol-text" aria-hidden /> Sala de revisión
                  </h2>
                  <p className="mt-0.5 text-[11px] text-fg-muted">Esperan la aprobación de una persona.</p>
                  <ul className="mt-3 flex flex-wrap gap-2">
                    {inReview.length === 0 && <li className="text-[11px] text-fg-faint">Nada por aprobar.</li>}
                    {inReview.map((i) => (
                      <li key={i.key}>
                        <Link href={`/projects/${project.key}?issue=${i.key}`} className="office-paper-static" title={i.title}>
                          {i.key}
                        </Link>
                      </li>
                    ))}
                  </ul>
                </section>
              )}
            </div>
          </div>
        </div>
      )}

      <SidePanel open={open != null} onClose={() => setOpenId(null)} width="narrow" title={open ? open.member.name : ""}>
        {open && <SeatDetail seat={open} roleName={roleNames[open.member.role] ?? open.member.role} projectKey={project.key} />}
      </SidePanel>
    </div>
  );
}

/** Estado de un puesto a partir de sus tarjetas y de las ejecuciones del agente. */
function seat(m: Member, issues: Issue[], runs: AgentRun[], gates: Set<string>, done: Set<string>, mode: AgentMode | null, now: number): Seat {
  const mine = issues.filter((i) => i.assigneeId === m.id && !done.has(i.status));
  const approvals = mine.filter((i) => gates.has(i.status));
  const queue = mine.filter((i) => !gates.has(i.status));
  if (m.kind === "AGENT") {
    const own = runs.filter((r) => r.agentId === m.id).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    const running = own.find((r) => r.status === "RUNNING");
    if (running) {
      const issue = issues.find((i) => i.key === running.issueKey) ?? null;
      return { member: m, state: "working", current: issue, queue, approvals, mode, note: `Trabajando en ${running.issueKey}` };
    }
    const last = own[0];
    const recent = last && Date.parse(last.finishedAt ?? last.createdAt) > now - RECENT_MS;
    if (recent && last.status === "FAILED") {
      return { member: m, state: "error", current: issues.find((i) => i.key === last.issueKey) ?? null, queue, approvals, mode, note: `No pudo terminar ${last.issueKey}` };
    }
    if (recent && last.summary && /pregunt/.test(last.summary)) {
      return { member: m, state: "asking", current: issues.find((i) => i.key === last.issueKey) ?? null, queue, approvals, mode, note: `Preguntó en ${last.issueKey}` };
    }
    if (queue.length > 0) {
      return {
        member: m,
        state: mode === "LOCAL" ? "queued" : "idle",
        current: queue[0],
        queue,
        approvals,
        mode,
        note: mode === "LOCAL" ? `${queue.length} esperando /pendientes` : `${queue.length} en su cola`,
      };
    }
    return { member: m, state: "idle", current: null, queue, approvals, mode, note: "Libre" };
  }
  if (approvals.length > 0) return { member: m, state: "review", current: approvals[0], queue, approvals, mode, note: `${approvals.length} por aprobar` };
  if (queue.length > 0) return { member: m, state: "busy", current: queue[0], queue, approvals, mode, note: `${queue.length} asignada(s)` };
  return { member: m, state: "idle", current: null, queue, approvals, mode, note: "Libre" };
}

function Whiteboard({ project, issues }: { project: Project; issues: Issue[] }) {
  const counts = project.columns.map((c) => ({ ...c, n: issues.filter((i) => i.status === c.key).length }));
  const max = Math.max(1, ...counts.map((c) => c.n));
  return (
    <section aria-label="Pizarra" className="office-board rounded-lg p-3">
      <h2 className="text-xs font-semibold text-fg-strong">Pizarra · {project.name}</h2>
      <ul className="mt-2 flex items-end gap-2 overflow-x-auto">
        {counts.map((c) => (
          <li key={c.key} className="flex w-14 shrink-0 flex-col items-center gap-1" title={`${c.name}: ${c.n}`}>
            <span className="tabular text-[11px] font-medium text-fg-strong">{c.n}</span>
            <span aria-hidden className="office-bar w-6 rounded-t" style={{ height: `${6 + (c.n / max) * 48}px` }} />
            <span className="w-full truncate text-center text-[10px] text-fg-muted">{c.name}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function OfficeList({ seats, roleNames, projectKey, meId }: { seats: Seat[]; roleNames: Record<string, string>; projectKey: string; meId: string }) {
  return (
    <div className="min-h-0 flex-1 overflow-auto px-4 pb-6 sm:px-6">
      <table className="w-full overflow-hidden rounded-lg border border-line bg-surface text-left text-[13px]">
        <thead className="border-b border-line bg-sunken text-xs text-fg-muted">
          <tr>
            <th scope="col" className="px-4 py-2 font-medium">Quién</th>
            <th scope="col" className="px-3 py-2 font-medium">Estado</th>
            <th scope="col" className="px-3 py-2 font-medium">Tarjeta</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {seats.map((s) => (
            <tr key={s.member.id}>
              <td className="px-4 py-2.5">
                <span className="flex items-center gap-2">
                  <Avatar member={s.member} size="sm" />
                  <span>
                    <span className="font-medium text-fg-strong">{s.member.name}</span>
                    {s.member.id === meId && <span className="ml-1 text-xs text-fg-muted">(tú)</span>}
                    <span className="block text-xs text-fg-faint">{roleNames[s.member.role] ?? s.member.role}</span>
                  </span>
                </span>
              </td>
              <td className="px-3 py-2.5 text-fg">{s.note}</td>
              <td className="px-3 py-2.5">
                {s.current ? (
                  <Link href={`/projects/${projectKey}?issue=${s.current.key}`} className="k-link tabular font-mono text-xs">
                    {s.current.key}
                  </Link>
                ) : (
                  "—"
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SeatDetail({ seat, roleName, projectKey }: { seat: Seat; roleName: string; projectKey: string }) {
  const list = (title: string, items: Issue[]) =>
    items.length > 0 && (
      <section className="flex flex-col gap-1.5">
        <h3 className="text-xs font-medium text-fg-muted">{title}</h3>
        <ul className="divide-y divide-line rounded-md border border-line">
          {items.map((i) => (
            <li key={i.key}>
              <Link href={`/projects/${projectKey}?issue=${i.key}`} className="flex items-center gap-2 px-3 py-2 text-[13px] hover:bg-hover">
                <span className="tabular font-mono text-xs text-fg-muted">{i.key}</span>
                <span className="min-w-0 truncate text-fg">{i.title}</span>
              </Link>
            </li>
          ))}
        </ul>
      </section>
    );
  return (
    <div className="flex flex-col gap-4 px-5 py-5">
      <div className="flex items-center gap-3">
        <Avatar member={seat.member} size="md" />
        <div>
          <p className="text-[13px] font-medium text-fg-strong">{roleName}</p>
          <p className="text-xs text-fg-muted">
            {seat.note}
            {seat.member.kind === "AGENT" && seat.mode && ` · ${seat.mode === "SERVER" ? "trabaja en el tablero" : "trabaja en la PC de una persona"}`}
          </p>
        </div>
      </div>
      {list("Por aprobar", seat.approvals)}
      {list("Su cola", seat.queue)}
      {seat.approvals.length + seat.queue.length === 0 && <p className="text-[13px] text-fg-faint">No tiene tarjetas abiertas en este tablero.</p>}
    </div>
  );
}

function ToolButton({
  onClick,
  icon: Icon,
  label,
  active,
  iconOnly,
}: {
  onClick: () => void;
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  active?: boolean;
  iconOnly?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      aria-label={iconOnly ? label : undefined}
      title={label}
      className={clsx(
        "inline-flex h-8 items-center gap-1.5 rounded-md px-2.5 text-xs",
        active ? "bg-hover font-medium text-fg-strong" : "text-fg-muted hover:bg-hover hover:text-fg-strong",
      )}
    >
      <Icon className="size-4" aria-hidden />
      {!iconOnly && label}
    </button>
  );
}

// ---- Papel que vuela ----

/** Anima un papel desde un lugar al otro. Devuelve si pudo (los dos lugares están en pantalla). */
function fly(container: HTMLElement, key: string, from: Spot, to: Spot): boolean {
  const a = container.querySelector<HTMLElement>(`[data-spot="${from}"]`);
  const b = container.querySelector<HTMLElement>(`[data-spot="${to}"]`);
  if (!a || !b) return false;
  const box = container.getBoundingClientRect();
  const ra = a.getBoundingClientRect();
  const rb = b.getBoundingClientRect();
  const x1 = ra.left + ra.width / 2 - box.left;
  const y1 = ra.top + ra.height / 3 - box.top;
  const x2 = rb.left + rb.width / 2 - box.left;
  const y2 = rb.top + rb.height / 3 - box.top;
  const paper = document.createElement("div");
  paper.className = "office-paper";
  paper.textContent = key;
  paper.style.left = `${x1}px`;
  paper.style.top = `${y1}px`;
  container.appendChild(paper);
  const lift = Math.min(160, 40 + Math.hypot(x2 - x1, y2 - y1) / 3);
  const animation = paper.animate(
    [
      { transform: "translate(-50%, -50%) scale(0.6) rotate(-8deg)", opacity: 0 },
      { transform: `translate(calc(-50% + ${(x2 - x1) / 2}px), calc(-50% + ${(y2 - y1) / 2 - lift}px)) scale(1.1) rotate(6deg)`, opacity: 1, offset: 0.5 },
      { transform: `translate(calc(-50% + ${x2 - x1}px), calc(-50% + ${y2 - y1}px)) scale(0.8) rotate(0deg)`, opacity: 0.9, offset: 0.92 },
      { transform: `translate(calc(-50% + ${x2 - x1}px), calc(-50% + ${y2 - y1}px)) scale(0.5)`, opacity: 0 },
    ],
    { duration: 1400, easing: "cubic-bezier(.3,.7,.3,1)" },
  );
  animation.onfinish = () => paper.remove();
  b.animate([{ transform: "scale(1)" }, { transform: "scale(1.04)" }, { transform: "scale(1)" }], { duration: 400, delay: 1250 });
  return true;
}

/** Un «ding» corto, sin archivos de audio. */
function chime() {
  try {
    const ctx = new AudioContext();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = "sine";
    osc.frequency.setValueAtTime(880, ctx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(1320, ctx.currentTime + 0.12);
    gain.gain.setValueAtTime(0.0001, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.08, ctx.currentTime + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.4);
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.45);
    osc.onended = () => void ctx.close();
  } catch {
    // Sin audio disponible: no pasa nada.
  }
}

// ---- Preferencias del navegador (sonido) y visibilidad ----

const SOUND_KEY = "kuador.office.sound";

function readSound(): boolean {
  try {
    return localStorage.getItem(SOUND_KEY) === "1";
  } catch {
    return false;
  }
}

function writeSound(on: boolean) {
  try {
    localStorage.setItem(SOUND_KEY, on ? "1" : "0");
  } catch {
    // Almacenamiento bloqueado: el sonido queda como estaba.
  }
  window.dispatchEvent(new Event("kuador-office-sound"));
}

function subscribeSound(cb: () => void) {
  window.addEventListener("kuador-office-sound", cb);
  window.addEventListener("storage", cb);
  return () => {
    window.removeEventListener("kuador-office-sound", cb);
    window.removeEventListener("storage", cb);
  };
}

function subscribeVisibility(cb: () => void) {
  document.addEventListener("visibilitychange", cb);
  return () => document.removeEventListener("visibilitychange", cb);
}
