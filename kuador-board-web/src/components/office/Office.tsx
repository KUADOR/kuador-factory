"use client";

import { clsx } from "clsx";
import { Building2, List, Maximize2, Pause, Play, Volume2, VolumeX } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";

import { Avatar } from "@/components/ui/Avatar";
import { SidePanel } from "@/components/ui/SidePanel";
import type { AgentMode, AgentRun, Issue, Me, Member, Project } from "@/lib/types";

import { Office3D } from "./Office3D";
import type { OfficeScene, SceneSeat, SceneZones } from "./scene3d";

/** Dónde está una tarjeta en la oficina: en un escritorio, en la sala de revisión, en la bandeja o en el archivo. */
type Spot = `desk:${string}` | "review" | "inbox" | "done";

/**
 * Estado de un puesto: trabajando, preguntó, falló, por aprobar, con tarjetas asignadas, esperando /pendientes
 * (agente local) o libre.
 */
export type DeskState = "working" | "asking" | "error" | "review" | "busy" | "queued" | "idle";

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
  const wrapper = useRef<HTMLDivElement>(null);
  const scene = useRef<OfficeScene | null>(null);
  const [rotate, setRotate] = useState(true);
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
  const sceneSeats: SceneSeat[] = seats.map((s) => ({
    id: s.member.id,
    name: s.member.name,
    role: roleNames[s.member.role] ?? s.member.role,
    color: s.member.avatarColor,
    agent: s.member.kind === "AGENT",
    laptop: s.member.kind === "AGENT" && s.mode === "LOCAL",
    state: s.state,
    screenKey: s.current?.key ?? null,
    note: s.note,
    me: s.member.id === me.id,
    approvals: s.approvals.length,
  }));
  const zones: SceneZones = {
    title: project.name,
    review: inReview.map((i) => i.key),
    inbox: inbox.length,
    archived,
    board: project.columns.map((c) => ({ name: c.name, n: issues.filter((i) => i.status === c.key).length })),
  };
  const legend = [
    { icon: "💻", n: seats.filter((s) => s.state === "working").length, label: "trabajando" },
    { icon: "❓", n: seats.filter((s) => s.state === "asking").length, label: "preguntaron" },
    { icon: "🖋️", n: inReview.length, label: "por aprobar" },
    { icon: "⚠️", n: seats.filter((s) => s.state === "error").length, label: "trabados" },
    { icon: "📥", n: inbox.length, label: "en la bandeja" },
  ].filter((l) => l.n > 0 || l.label === "trabajando");

  // Papeles que vuelan cuando una tarjeta cambia de lugar. Se dibujan fuera de React (son efímeros).
  const spots = useRef<Map<string, Spot> | null>(null);
  const issuesKey = issues.map((i) => `${i.key}:${i.status}:${i.assigneeId}`).join("|");
  useEffect(() => {
    const next = new Map(issues.map((i) => [i.key, spotOf(i)] as const));
    const before = spots.current;
    spots.current = next;
    if (!before || !scene.current || view !== "office") return;
    let flew = false;
    for (const [key, to] of next) {
      const from = before.get(key);
      if (!from || from === to) continue;
      flew = scene.current.fly(from, to, key) || flew;
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
        {view === "office" && (
          <ToolButton onClick={() => setRotate(!rotate)} icon={rotate ? Pause : Play} label={rotate ? "Detener el giro de la cámara" : "Girar la cámara sola"} iconOnly />
        )}
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
        <div className="relative min-h-0 flex-1 px-4 pb-4 sm:px-6">
          <div className="relative h-full min-h-[26rem] overflow-hidden rounded-xl border border-line">
            <Office3D
              seats={sceneSeats}
              zones={zones}
              onSelect={setOpenId}
              sceneRef={scene}
              autoRotate={rotate}
              paused={hidden}
              fallback={<OfficeList seats={seats} roleNames={roleNames} projectKey={project.key} meId={me.id} />}
            />
            <div className="pointer-events-none absolute top-3 left-3 flex max-w-[calc(100%-1.5rem)] flex-wrap gap-1.5">
              {legend.map((l) => (
                <span key={l.label} className="office3d-chip">
                  {l.icon} {l.n} {l.label}
                </span>
              ))}
            </div>
            <p className="pointer-events-none absolute bottom-3 left-3 hidden text-[11px] text-fg-muted sm:block">
              Arrastra para girar · rueda o pellizco para acercar · clic en un puesto para ver su cola
            </p>
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
