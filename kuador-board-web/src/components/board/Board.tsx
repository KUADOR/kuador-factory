"use client";

import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  MouseSensor,
  TouchSensor,
  closestCorners,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import { arrayMove, sortableKeyboardCoordinates } from "@dnd-kit/sortable";
import { ArrowRight, Check, ListChecks, Plus, Search, SlidersHorizontal, X } from "lucide-react";
import { clsx } from "clsx";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState, useTransition } from "react";

import { BottomSheet } from "@/components/ui/BottomSheet";

import { firstStageAhead } from "./nextStep";
import { createIssueAction, moveIssueAction, updateColumnsAction } from "@/app/actions";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { SelectInput, TextInput } from "@/components/ui/Field";
import { TypeIcon } from "@/components/ui/IssueIcons";
import { SidePanel } from "@/components/ui/SidePanel";
import { notify } from "@/components/ui/Toaster";
import { allowedTypes, typeLabel } from "@/lib/labels";
import { useAccess } from "@/components/shell/AccessContext";
import { roleLabel } from "@/lib/labels";
import type { AgentMode, AgentRole, AgentRun, BoardColumn, BoardView, CardPlan, ColumnCategory, Issue, IssueStatus, IssueType, Me, Member, Project } from "@/lib/types";

import { AddColumn, Column, type ColumnActions, type Selection } from "./Column";
import { COLUMN_DROP_PREFIX } from "./dnd";
import { IssueCard } from "./IssueCard";
import { IssueDetail } from "./IssueDetail";
import { IssueForm, emptyValues } from "./IssueForm";
import { ViewSwitch } from "./ViewSwitch";

type Columns = Record<IssueStatus, string[]>;


function group(project: Project, issues: Issue[]): Columns {
  const columns: Columns = Object.fromEntries(project.columns.map((c) => [c.key, [] as string[]]));
  const first = project.columns[0]?.key;
  [...issues]
    .sort((a, b) => a.position - b.position)
    // Un issue en una columna que ya no existe se muestra en la primera para no perderlo de vista.
    .forEach((i) => (columns[i.status] ?? (first ? columns[first] : []))?.push(i.key));
  return columns;
}

function columnOf(columns: Columns, id: string): IssueStatus | undefined {
  if (id.startsWith(COLUMN_DROP_PREFIX)) return id.slice(COLUMN_DROP_PREFIX.length);
  return Object.keys(columns).find((s) => columns[s].includes(id));
}

export function Board({
  project,
  issues,
  members,
  runs,
  me,
  roles,
  initialOpenKey,
  modes = {},
  everyone,
  history,
}: {
  project: Project;
  issues: Issue[];
  /** Lo que el tablero no trae de las columnas de cierre: totales, avance de las épicas y títulos de las que no vienen. */
  history?: Pick<BoardView, "totals" | "progress" | "parents">;
  members: Member[];
  /** Ejecuciones recientes de agentes: las que siguen en curso se ven en su tarjeta. */
  runs: AgentRun[];
  /** Quién mira: decide qué acciones de etapa ve (tomar, entregar, aprobar). */
  me: Me;
  roles: AgentRole[];
  initialOpenKey?: string;
  /** Dónde trabaja cada agente del equipo (tablero o PC de una persona). */
  modes?: Record<string, AgentMode>;
  /** Todos los miembros del espacio, para mostrar el nombre de quien escribió aunque no esté en este tablero. */
  everyone?: Member[];
}) {
  const roleName = (key: string) => roleLabel(key, roles.find((r) => r.key === key)?.name);
  const { canEdit } = useAccess();
  // Estado local para arrastrar sin esperar al servidor; se resincroniza cuando llegan datos nuevos.
  const [source, setSource] = useState({ project, issues });
  const [columns, setColumns] = useState(() => group(project, issues));
  if (source.issues !== issues || source.project !== project) {
    setSource({ project, issues });
    setColumns(group(project, issues));
  }
  const types = allowedTypes(project);

  const byKey = new Map(issues.map((i) => [i.key, i]));
  const memberById = new Map(members.map((m) => [m.id, m]));
  const epics = issues.filter((i) => i.type === "EPIC");
  const epicTitles = new Map([...Object.entries(history?.parents ?? {}), ...epics.map((e) => [e.key, e.title] as [string, string])]);
  const plans = cardPlans(issues, byKey, project, roleName, history?.progress);
  const working = new Map(
    runs.filter((r) => r.status === "RUNNING" && r.projectKey === project.key).flatMap((r) => {
      const agent = memberById.get(r.agentId);
      return agent ? [[r.issueKey, agent] as const] : [];
    }),
  );
  // Responsables con trabajo en el proyecto primero: son los filtros rápidos que más se usan.
  const busy = new Set(issues.map((i) => i.assigneeId).filter(Boolean));
  const quickMembers = [...members].sort((a, b) => Number(busy.has(b.id)) - Number(busy.has(a.id))).slice(0, 8);

  const [activeKey, setActiveKey] = useState<string | null>(null);
  const [openKey, setOpenKey] = useState<string | null>(initialOpenKey ?? null);
  // Un enlace «?issue=CLAVE» (notificación, actividad, documento) abre la tarjeta aunque el tablero ya estuviera abierto.
  const linked = useSearchParams().get("issue")?.toUpperCase() ?? null;
  const [lastLinked, setLastLinked] = useState(linked);
  if (linked !== lastLinked) {
    setLastLinked(linked);
    if (linked) setOpenKey(linked);
  }
  /** Abre o cierra el detalle y lo deja en la URL, para compartirlo y para que «atrás» funcione. */
  const showIssue = (key: string | null) => {
    setOpenKey(key);
    const url = new URL(window.location.href);
    if (key) url.searchParams.set("issue", key);
    else url.searchParams.delete("issue");
    window.history.replaceState(window.history.state, "", url);
  };
  const [creatingIn, setCreatingIn] = useState<IssueStatus | null>(null);
  const [text, setText] = useState("");
  const [assignee, setAssignee] = useState("");
  const [type, setType] = useState<IssueType | "">("");
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, startCreate] = useTransition();
  const [, startMove] = useTransition();
  const [, startColumns] = useTransition();
  const origin = useRef<Columns | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  // Selección de varias tarjetas para cambiarlas de columna de una vez.
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<ReadonlySet<string>>(() => new Set());
  const [bulkTarget, setBulkTarget] = useState<string | null>(null);
  const [bulkProgress, setBulkProgress] = useState<{ done: number; total: number } | null>(null);
  const stopSelecting = () => {
    setSelecting(false);
    setSelected(new Set());
    setBulkTarget(null);
  };
  const selection: Selection = {
    selected,
    toggle: (key) => {
      setSelecting(true);
      setSelected((prev) => {
        const next = new Set(prev);
        if (next.has(key)) next.delete(key);
        else next.add(key);
        return next;
      });
    },
    toggleAll: (keys) =>
      setSelected((prev) => {
        const next = new Set(prev);
        const all = keys.every((k) => next.has(k));
        keys.forEach((k) => (all ? next.delete(k) : next.add(k)));
        return next;
      }),
  };
  // Escape sale del modo selección (salvo con el detalle abierto, que se cierra primero).
  useEffect(() => {
    if (!selecting) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !bulkProgress) {
        setSelecting(false);
        setSelected(new Set());
        setBulkTarget(null);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [selecting, bulkProgress]);
  // Celular: qué columna está a la vista (el tablero se desliza de a una) y sus chips.
  const scrollerRef = useRef<HTMLDivElement>(null);
  const chipsRef = useRef<HTMLDivElement>(null);
  const [currentColumn, setCurrentColumn] = useState(0);
  const columnEls = () => [...(scrollerRef.current?.querySelectorAll<HTMLElement>("section[data-column]") ?? [])];
  const onBoardScroll = () => {
    const el = scrollerRef.current;
    if (!el) return;
    const left = el.scrollLeft + 16;
    const sections = columnEls();
    let index = 0;
    sections.forEach((sec, i) => {
      if (sec.offsetLeft - sec.offsetWidth / 2 <= left) index = i;
    });
    if (index !== currentColumn) {
      setCurrentColumn(index);
      (chipsRef.current?.children[index] as HTMLElement | undefined)?.scrollIntoView({ block: "nearest", inline: "center", behavior: "smooth" });
    }
  };
  const goToColumn = (index: number) => {
    const sec = columnEls()[index];
    if (sec && scrollerRef.current) scrollerRef.current.scrollTo({ left: sec.offsetLeft - 16, behavior: "smooth" });
  };

  const sensors = useSensors(
    // Con el mouse se arrastra al moverlo; en el celular hay que mantener presionada la tarjeta, así deslizar el dedo
    // mueve el tablero (scroll horizontal) en vez de arrastrar.
    useSensor(MouseSensor, { activationConstraint: { distance: 6 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 250, tolerance: 8 } }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
      keyboardCodes: { start: ["Space"], cancel: ["Escape"], end: ["Space", "Enter"] },
    }),
  );

  // Reglas de transición: desde la columna de origen del arrastre, a cuáles se puede soltar.
  const rules = project.transitions;
  const allowedMoves = new Set(rules.allowed.map((t) => `${t.from}>${t.to}`));
  const canMove = (from: string | undefined, to: string) => !rules.restricted || !from || from === to || allowedMoves.has(`${from}>${to}`);
  const [dragFrom, setDragFrom] = useState<IssueStatus | undefined>(undefined);
  const dragFromName = project.columns.find((c) => c.key === dragFrom)?.name;

  /** Guarda el flujo completo (las columnas existentes conservan su clave; las nuevas van sin clave). */
  const saveColumns = (next: (Omit<BoardColumn, "key"> & { key: string | null })[], success: string) =>
    startColumns(async () => {
      const result = await updateColumnsAction(project.key, next);
      if (result.ok) notify.ok(success);
      else notify.error("No se pudieron cambiar los pasos", result.error);
    });
  const actionsFor = (index: number): ColumnActions => {
    const cols = project.columns;
    const column = cols[index];
    const replace = (patch: Partial<BoardColumn>) => cols.map((c, i) => (i === index ? { ...c, ...patch } : c));
    return {
      rename: (name) => saveColumns(replace({ name }), `Paso renombrado a «${name}»`),
      setCategory: (category: ColumnCategory) => saveColumns(replace({ category }), `«${column.name}» actualizado`),
      move: (delta) => {
        const next = [...cols];
        const [c] = next.splice(index, 1);
        next.splice(index + delta, 0, c);
        saveColumns(next, `«${column.name}» movido`);
      },
      remove: () => saveColumns(cols.filter((_, i) => i !== index), `Paso «${column.name}» quitado`),
      canMoveLeft: index > 0,
      canMoveRight: index < cols.length - 1,
      canRemove: cols.length > 1,
    };
  };

  const query = text.trim().toLowerCase();
  const visible = (issue: Issue | undefined): issue is Issue =>
    !!issue &&
    (!query ||
      issue.title.toLowerCase().includes(query) ||
      issue.key.toLowerCase().includes(query) ||
      issue.labels.some((l) => l.includes(query))) &&
    (!assignee || (assignee === "none" ? !issue.assigneeId : issue.assigneeId === assignee)) &&
    (!type || issue.type === type);

  function onDragStart({ active }: DragStartEvent) {
    origin.current = columns;
    setActiveKey(String(active.id));
    setDragFrom(columnOf(columns, String(active.id)));
  }

  // Al cruzar a otra columna, el issue se inserta ahí para que el hueco se vea en vivo.
  function onDragOver({ active, over }: DragOverEvent) {
    if (!over) return;
    const id = String(active.id);
    const overId = String(over.id);
    setColumns((prev) => {
      const from = columnOf(prev, id);
      const to = columnOf(prev, overId);
      if (!from || !to || from === to) return prev;
      // No se previsualiza la tarjeta en una columna a la que el flujo no deja llegar.
      if (origin.current && !canMove(columnOf(origin.current, id), to)) return prev;
      const target = [...prev[to]];
      const overIndex = target.indexOf(overId);
      target.splice(overIndex < 0 ? target.length : overIndex, 0, id);
      return { ...prev, [from]: prev[from].filter((k) => k !== id), [to]: target };
    });
  }

  /** Columna bajo el puntero al soltar (las bloqueadas no son zona de soltar, así que `over` no lo dice). */
  function columnUnderPointer(event: DragEndEvent): string | undefined {
    const start = event.activatorEvent as PointerEvent | KeyboardEvent | null;
    if (!start || !("clientX" in start)) return undefined;
    const x = start.clientX + event.delta.x;
    const y = start.clientY + event.delta.y;
    return (document.elementsFromPoint(x, y).find((el) => el instanceof HTMLElement && el.dataset.column) as HTMLElement | undefined)?.dataset.column;
  }

  function onDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    const id = String(active.id);
    const before = origin.current;
    setActiveKey(null);
    setDragFrom(undefined);
    origin.current = null;
    if (!over || !before) {
      if (before) setColumns(before);
      return;
    }
    let next = columns;
    const status = columnOf(next, id);
    if (!status) return;
    const items = next[status];
    const overIndex = items.indexOf(String(over.id));
    const currentIndex = items.indexOf(id);
    if (overIndex >= 0 && overIndex !== currentIndex) {
      next = { ...next, [status]: arrayMove(items, currentIndex, overIndex) };
      setColumns(next);
    }
    const index = next[status].indexOf(id);
    const fromStatus = columnOf(before, id);
    // Soltar sobre una columna bloqueada devuelve la tarjeta a su origen, aunque haya cruzado otra permitida.
    const droppedOn = columnUnderPointer(event) ?? columnOf(next, String(over.id));
    if (droppedOn && droppedOn !== status && !canMove(fromStatus, droppedOn)) {
      setColumns(before);
      notify.error("Movimiento no permitido", `El flujo no deja pasar de «${project.columns.find((c) => c.key === fromStatus)?.name}» a «${project.columns.find((c) => c.key === droppedOn)?.name}».`);
      return;
    }
    if (fromStatus === status && before[status].indexOf(id) === index) return;
    if (!canMove(fromStatus, status)) {
      setColumns(before);
      notify.error("Movimiento no permitido", "El flujo de este tablero no permite ese movimiento.");
      return;
    }

    startMove(async () => {
      const result = await moveIssueAction(project.key, id, status, index);
      if (!result.ok) {
        notify.error(`No se pudo mover ${id}`, result.error);
        setColumns(before);
      }
    });
  }

  /**
   * Mueve las tarjetas seleccionadas a una columna, una por una y con las mismas reglas que arrastrar (transiciones,
   * puertas, épicas que esperan sus tareas). Lo que no se pudo mover queda marcado, con el motivo.
   */
  async function moveSelected(to: string) {
    const target = project.columns.find((c) => c.key === to);
    if (!target) return;
    const keys = [...selected].filter((k) => byKey.has(k));
    const failures: { key: string; reason: string }[] = [];
    let moved = 0;
    setBulkProgress({ done: 0, total: keys.length });
    for (const [i, key] of keys.entries()) {
      const issue = byKey.get(key)!;
      if (issue.status === to) {
        moved++;
      } else if (!canMove(issue.status, to)) {
        failures.push({ key, reason: `el flujo no deja pasar de «${project.columns.find((c) => c.key === issue.status)?.name}» a «${target.name}»` });
      } else {
        // Al final de la columna destino: la API ajusta la posición al largo real.
        const result = await moveIssueAction(project.key, key, to, 100_000);
        if (result.ok) moved++;
        else failures.push({ key, reason: result.error });
      }
      setBulkProgress({ done: i + 1, total: keys.length });
    }
    setBulkProgress(null);
    if (failures.length === 0) {
      notify.ok(`${moved} ${moved === 1 ? "tarjeta pasó" : "tarjetas pasaron"} a ${target.name}`);
      stopSelecting();
      return;
    }
    notify.error(
      `${moved} movidas, ${failures.length} no`,
      failures
        .slice(0, 3)
        .map((f) => `${f.key}: ${f.reason}`)
        .join(" · ") + (failures.length > 3 ? ` · y ${failures.length - 3} más` : ""),
    );
    // Quedan marcadas solo las que no se movieron, para revisarlas.
    setSelected(new Set(failures.map((f) => f.key)));
  }

  const openIssue = openKey ? byKey.get(openKey) : undefined;
  // Una tarjeta que el tablero no trajo (terminada hace tiempo): se pide a la página con ella en la URL, una vez.
  const router = useRouter();
  const asked = useRef<string | null>(null);
  useEffect(() => {
    if (!openKey || openIssue || asked.current === openKey) return;
    asked.current = openKey;
    const url = new URL(window.location.href);
    url.searchParams.set("issue", openKey);
    router.replace(url.pathname + url.search, { scroll: false });
  }, [openKey, openIssue, router]);
  const activeIssue = activeKey ? byKey.get(activeKey) : undefined;
  const filtering = Boolean(query || assignee || type);
  const clearFilters = () => {
    setText("");
    setAssignee("");
    setType("");
  };

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      {openKey && !openIssue && (
        <p role="status" className="mx-6 mt-3 flex items-center gap-2 text-[13px] text-warn">
          No encontré {openKey} en este tablero.
          <button type="button" onClick={() => showIssue(null)} className="k-link ml-auto text-xs">
            Cerrar
          </button>
        </p>
      )}
      <div className="flex flex-wrap items-center gap-2 px-4 py-2.5 sm:px-6 md:py-3">
        <div className="relative min-w-0 flex-1 md:w-56 md:flex-none">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 z-10 size-4 -translate-y-1/2 text-fg-faint" aria-hidden />
          <TextInput size="sm" value={text} onChange={(e) => setText(e.target.value)} placeholder="Buscar en el tablero" aria-label="Buscar tarjetas" className="pl-8" />
        </div>
        {/* Celular: responsable y tipo en una hoja; la fila de avatares no cabe. */}
        <Button tone={assignee || type ? "primary" : "secondary"} size="sm" icon={SlidersHorizontal} className="md:hidden" onClick={() => setFiltersOpen(true)}>
          Filtros{assignee || type ? ` · ${Number(!!assignee) + Number(!!type)}` : ""}
        </Button>
        {canEdit && (
          <Button tone="primary" size="sm" icon={Plus} iconOnly label="Crear tarjeta" className="md:hidden" onClick={() => setCreatingIn(project.columns[0]?.key ?? null)} />
        )}
        <div role="group" aria-label="Filtrar por responsable" className="hidden items-center gap-1 pl-1 md:flex">
          {quickMembers.map((m) => (
            <button
              key={m.id}
              type="button"
              aria-pressed={assignee === m.id}
              onClick={() => setAssignee(assignee === m.id ? "" : m.id)}
              title={m.name}
              className={clsx(
                "rounded-full p-0.5 ring-2 transition-colors",
                assignee === m.id ? "ring-sol" : "ring-transparent hover:ring-line-hover",
              )}
            >
              <Avatar member={m} size="sm" />
            </button>
          ))}
          <button
            type="button"
            aria-pressed={assignee === "none"}
            onClick={() => setAssignee(assignee === "none" ? "" : "none")}
            className={clsx("ml-2 rounded-md px-2 py-1 text-xs", assignee === "none" ? "bg-sol/15 text-fg-strong" : "text-fg-muted hover:bg-hover hover:text-fg-strong")}
          >
            Sin asignar
          </button>
        </div>
        <div className="hidden w-44 md:block">
          <SelectInput
            size="sm"
            ariaLabel="Filtrar por tipo"
            placeholder="Todos los tipos"
            options={types.map((t) => ({ value: t, label: typeLabel(t, project), leading: <TypeIcon type={t} projectType={project} /> }))}
            value={type || null}
            onChange={(v) => setType(v ?? "")}
          />
        </div>
        {filtering && (
          <Button tone="ghost" size="sm" icon={X} className={clsx(!query && "hidden md:inline-flex")} onClick={clearFilters}>
            Limpiar filtros
          </Button>
        )}
        <ViewSwitch projectKey={project.key} view="board" className="md:ml-auto" />
        {canEdit && (
          <Button
            tone={selecting ? "primary" : "ghost"}
            size="sm"
            icon={ListChecks}
            className=""
            aria-pressed={selecting}
            title={selecting ? "Salir de la selección (Esc)" : "Marcar varias tarjetas para cambiarlas de paso (también ⌘/Ctrl + clic)"}
            onClick={() => (selecting ? stopSelecting() : setSelecting(true))}
          >
            {selecting ? "Terminar selección" : "Seleccionar"}
          </Button>
        )}
        {canEdit && (
          <Button tone="primary" size="sm" icon={Plus} className="hidden md:inline-flex" onClick={() => setCreatingIn(project.columns[0]?.key ?? null)}>
            Crear tarjeta
          </Button>
        )}
      </div>

      <BottomSheet
        open={filtersOpen}
        onClose={() => setFiltersOpen(false)}
        title="Filtrar el tablero"
        footer={
          <>
            <Button tone="ghost" className="flex-1" onClick={clearFilters} disabled={!filtering}>
              Limpiar
            </Button>
            <Button tone="primary" className="flex-1" onClick={() => setFiltersOpen(false)}>
              Ver tablero
            </Button>
          </>
        }
      >
        <p className="px-3 pt-1 pb-1 text-[11px] font-medium tracking-wide text-fg-faint uppercase">Responsable</p>
        <ul>
          {[{ id: "", name: "Todos" }, ...members.map((m) => ({ id: m.id, name: m.name, m })), { id: "none", name: "Sin asignar" }].map((o) => (
            <li key={o.id || "all"}>
              <button
                type="button"
                onClick={() => setAssignee(o.id)}
                className={clsx("flex h-12 w-full items-center gap-3 rounded-lg px-3 text-left text-[15px]", assignee === o.id ? "bg-selected font-medium text-fg-strong" : "text-fg active:bg-hover")}
              >
                {"m" in o && <Avatar member={o.m} size="sm" />}
                <span className="min-w-0 flex-1 truncate">{o.name}</span>
                {assignee === o.id && <Check className="size-4 text-sol-text" aria-hidden />}
              </button>
            </li>
          ))}
        </ul>
        <p className="mt-2 border-t border-line px-3 pt-3 pb-1 text-[11px] font-medium tracking-wide text-fg-faint uppercase">Tipo</p>
        <ul>
          {([""] as (IssueType | "")[]).concat(types).map((t) => (
            <li key={t || "all"}>
              <button
                type="button"
                onClick={() => setType(t)}
                className={clsx("flex h-12 w-full items-center gap-3 rounded-lg px-3 text-left text-[15px]", type === t ? "bg-selected font-medium text-fg-strong" : "text-fg active:bg-hover")}
              >
                {t && <TypeIcon type={t} projectType={project} />}
                <span className="flex-1">{t ? typeLabel(t, project) : "Todos los tipos"}</span>
                {type === t && <Check className="size-4 text-sol-text" aria-hidden />}
              </button>
            </li>
          ))}
        </ul>
      </BottomSheet>

      {/* Celular: una columna a la vez; los chips dicen dónde estás y saltan a las demás. */}
      <div role="tablist" aria-label="Pasos" className="flex shrink-0 gap-1.5 overflow-x-auto px-4 pb-2 md:hidden" ref={chipsRef}>
        {project.columns.map((c, i) => {
          const count = (columns[c.key] ?? []).map((k) => byKey.get(k)).filter(filtering ? visible : (x): x is Issue => !!x).length;
          return (
            <button
              key={c.key}
              type="button"
              role="tab"
              aria-selected={i === currentColumn}
              onClick={() => goToColumn(i)}
              className={clsx(
                "flex h-8 shrink-0 items-center gap-1.5 rounded-full border px-3 text-[13px] whitespace-nowrap",
                i === currentColumn ? "border-sol/60 bg-sol/15 font-medium text-fg-strong" : "border-line text-fg-muted",
              )}
            >
              {c.name}
              <span className="tabular text-xs text-fg-faint">{count}</span>
            </button>
          );
        })}
      </div>

      <DndContext
        id={`board-${project.key}`}
        sensors={sensors}
        collisionDetection={closestCorners}
        onDragStart={onDragStart}
        onDragOver={onDragOver}
        onDragEnd={onDragEnd}
        onDragCancel={() => {
          if (origin.current) setColumns(origin.current);
          origin.current = null;
          setActiveKey(null);
          setDragFrom(undefined);
        }}
      >
        <div
          ref={scrollerRef}
          onScroll={onBoardScroll}
          className="relative flex min-h-0 flex-1 snap-x snap-mandatory scroll-px-4 gap-2.5 overflow-x-auto overscroll-x-contain px-4 pb-3 md:snap-none md:px-6 md:pb-4"
        >
          {project.columns.map((column, index) => (
            <Column
              key={column.key + column.name + column.category}
              column={column}
              actions={actionsFor(index)}
              blocked={dragFrom && !canMove(dragFrom, column.key) ? `No se puede pasar desde «${dragFromName}»` : undefined}
              stage={project.stages.find((s) => s.column === column.key)}
              startsAt={column.category === "TODO" ? firstStageAhead(project, column.key)?.name : undefined}
              roleName={roleName}
              projectType={project}
              issues={(columns[column.key] ?? []).map((k) => byKey.get(k)).filter(filtering ? visible : (i): i is Issue => !!i)}
              members={memberById}
              epics={epicTitles}
              plans={plans}
              working={working}
              onOpen={showIssue}
              onCreate={canEdit ? setCreatingIn : undefined}
              total={column.category === "DONE" ? history?.totals[column.key] : undefined}
              listHref={`/projects/${project.key}?view=list&status=${encodeURIComponent(column.key)}`}
              selection={canEdit ? { ...selection, active: selecting } : undefined}
            />
          ))}
          {canEdit && (
            <AddColumn
              disabled={project.columns.length >= 12}
              onAdd={(name) => saveColumns([...project.columns, { key: null, name, category: "IN_PROGRESS" }], `Paso «${name}» agregado`)}
            />
          )}
        </div>
        <DragOverlay>
          {activeIssue && (
            <IssueCard
              issue={activeIssue}
              assignee={activeIssue.assigneeId ? memberById.get(activeIssue.assigneeId) : undefined}
              epicTitle={activeIssue.parentKey ? epicTitles.get(activeIssue.parentKey) : undefined}
              projectType={project}
              workingAgent={working.get(activeIssue.key)}
              dragging
            />
          )}
        </DragOverlay>
      </DndContext>

      {openIssue && (
        <IssueDetail
          key={openIssue.key}
          issue={openIssue}
          project={project}
          members={members}
          epics={epics}
          workingAgent={working.get(openIssue.key)}
          me={me}
          roleName={roleName}
          onOpenIssue={showIssue}
          onClose={() => showIssue(null)}
          modes={modes}
          everyone={everyone}
          tasksVersion={issues
            .filter((i) => i.parentKey === openIssue.key && i.workRole)
            .map((i) => `${i.key}:${i.status}`)
            .join(",")}
          lastRun={runs.filter((r) => r.issueKey === openIssue.key).sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]}
        />
      )}

      {selecting && (
        <div
          role="toolbar"
          aria-label="Acciones con las tarjetas marcadas"
          className="fixed inset-x-3 bottom-[max(0.75rem,env(safe-area-inset-bottom))] z-40 mx-auto flex max-w-xl flex-wrap items-center gap-2 rounded-lg border border-line-strong bg-raised px-3 py-2 shadow-float"
        >
          <span className="tabular text-[13px] text-fg-strong">
            {bulkProgress ? `Moviendo ${bulkProgress.done}/${bulkProgress.total}…` : selected.size === 0 ? "Marca las tarjetas" : `${selected.size} ${selected.size === 1 ? "marcada" : "marcadas"}`}
          </span>
          <div className="ml-auto w-44">
            <SelectInput
              size="sm"
              ariaLabel="Paso destino"
              placeholder="Mover a…"
              options={project.columns.map((c) => ({ value: c.key, label: c.name }))}
              value={bulkTarget}
              disabled={!!bulkProgress}
              onChange={(v) => setBulkTarget(v ?? null)}
            />
          </div>
          <Button
            size="sm"
            tone="primary"
            icon={ArrowRight}
            loading={!!bulkProgress}
            disabled={!bulkTarget || selected.size === 0}
            onClick={() => bulkTarget && void moveSelected(bulkTarget)}
          >
            Mover
          </Button>
          <Button size="sm" tone="ghost" icon={X} iconOnly label="Cancelar selección" disabled={!!bulkProgress} onClick={stopSelecting} />
        </div>
      )}

      <SidePanel
        open={creatingIn != null}
        onClose={() => setCreatingIn(null)}
        width="narrow"
        title={`Nueva tarjeta en ${project.key}`}
      >
        {creatingIn && (
          <IssueForm
            key={creatingIn}
            project={project}
            initial={emptyValues(creatingIn, project)}
            members={members}
            epics={epics}
            layout="stacked"
            submitLabel="Crear tarjeta"
            pending={creating}
            error={createError}
            onSubmit={(values) =>
              startCreate(async () => {
                const result = await createIssueAction(project.key, values);
                if (result.ok) {
                  setCreatingIn(null);
                  setCreateError(null);
                  notify.ok(`${result.data.key} creado`, result.data.title);
                } else {
                  setCreateError(result.error);
                }
              })
            }
          />
        )}
      </SidePanel>
    </div>
  );
}

/** Plan de las épicas en las tarjetas: rol de cada tarea, a qué tareas espera, cuántas terminó cada épica y si su reparto espera aprobación. */
function cardPlans(
  issues: Issue[],
  byKey: Map<string, Issue>,
  project: Project,
  roleName: (key: string) => string,
  /** Avance de cada épica contado en la API: las tareas terminadas pueden no venir en {@code issues}. */
  counted?: Record<string, { done: number; total: number }>,
): Map<string, CardPlan> {
  const done = new Set(project.columns.filter((c) => c.category === "DONE").map((c) => c.key));
  const first = project.columns[0]?.key;
  const plans = new Map<string, CardPlan>();
  for (const issue of issues) {
    if (!issue.workRole) continue;
    const waitingFor = (issue.dependsOn ?? []).filter((k) => {
      const dep = byKey.get(k);
      return dep && !done.has(dep.status);
    });
    plans.set(issue.key, { ...plans.get(issue.key), role: roleName(issue.workRole), waitingFor: done.has(issue.status) ? [] : waitingFor });
    const epic = issue.parentKey ? byKey.get(issue.parentKey) : undefined;
    if (epic) {
      const current = plans.get(epic.key) ?? {};
      const progress = current.progress ?? { done: 0, total: 0 };
      const stage = project.stages.find((st) => st.column === epic.status);
      // La API no exige que la épica esté libre para aprobar el reparto: el aviso tampoco.
      const awaiting = issue.status === first && !!stage && !stage.gate && stage.roles.length > 0;
      plans.set(epic.key, {
        ...current,
        progress: counted?.[epic.key] ?? { done: progress.done + (done.has(issue.status) ? 1 : 0), total: progress.total + 1 },
        planPending: current.planPending || awaiting,
      });
    }
  }
  // Una épica con todas sus tareas terminadas (y fuera de la vista) igual muestra su avance.
  for (const [key, progress] of Object.entries(counted ?? {})) {
    if (byKey.has(key) && !plans.get(key)?.progress) plans.set(key, { ...plans.get(key), progress });
  }
  return plans;
}
