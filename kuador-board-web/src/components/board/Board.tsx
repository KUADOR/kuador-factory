"use client";

import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  closestCorners,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import { arrayMove, sortableKeyboardCoordinates } from "@dnd-kit/sortable";
import { Plus, Search, X } from "lucide-react";
import { clsx } from "clsx";
import { useRef, useState, useTransition } from "react";

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
import type { AgentRole, AgentRun, BoardColumn, CardPlan, ColumnCategory, Issue, IssueStatus, IssueType, Me, Member, Project } from "@/lib/types";

import { AddColumn, Column, type ColumnActions } from "./Column";
import { COLUMN_DROP_PREFIX } from "./dnd";
import { IssueCard } from "./IssueCard";
import { IssueDetail } from "./IssueDetail";
import { IssueForm, emptyValues } from "./IssueForm";

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
}: {
  project: Project;
  issues: Issue[];
  members: Member[];
  /** Ejecuciones recientes de agentes: las que siguen en curso se ven en su tarjeta. */
  runs: AgentRun[];
  /** Quién mira: decide qué acciones de etapa ve (tomar, entregar, aprobar). */
  me: Me;
  roles: AgentRole[];
  initialOpenKey?: string;
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
  const epicTitles = new Map(epics.map((e) => [e.key, e.title]));
  const plans = cardPlans(issues, byKey, project, roleName);
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
  const [creatingIn, setCreatingIn] = useState<IssueStatus | null>(null);
  const [text, setText] = useState("");
  const [assignee, setAssignee] = useState("");
  const [type, setType] = useState<IssueType | "">("");
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, startCreate] = useTransition();
  const [, startMove] = useTransition();
  const [, startColumns] = useTransition();
  const origin = useRef<Columns | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
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
      else notify.error("No se pudo cambiar el flujo", result.error);
    });
  const actionsFor = (index: number): ColumnActions => {
    const cols = project.columns;
    const column = cols[index];
    const replace = (patch: Partial<BoardColumn>) => cols.map((c, i) => (i === index ? { ...c, ...patch } : c));
    return {
      rename: (name) => saveColumns(replace({ name }), `Columna renombrada a «${name}»`),
      setCategory: (category: ColumnCategory) => saveColumns(replace({ category }), `«${column.name}» actualizada`),
      move: (delta) => {
        const next = [...cols];
        const [c] = next.splice(index, 1);
        next.splice(index + delta, 0, c);
        saveColumns(next, `«${column.name}» movida`);
      },
      remove: () => saveColumns(cols.filter((_, i) => i !== index), `Columna «${column.name}» quitada`),
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
      notify.error("Movimiento no permitido", "Las reglas de transición del proyecto no permiten ese paso.");
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

  const openIssue = openKey ? byKey.get(openKey) : undefined;
  const activeIssue = activeKey ? byKey.get(activeKey) : undefined;
  const filtering = Boolean(query || assignee || type);

  return (
    <div className="flex flex-1 flex-col">
      <div className="flex flex-wrap items-center gap-2 px-6 py-3">
        <div className="relative w-56">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 z-10 size-4 -translate-y-1/2 text-fg-faint" aria-hidden />
          <TextInput size="sm" value={text} onChange={(e) => setText(e.target.value)} placeholder="Buscar en el tablero" aria-label="Buscar issues" className="pl-8" />
        </div>
        <div role="group" aria-label="Filtrar por responsable" className="flex items-center gap-1 pl-1">
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
        <div className="w-44">
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
          <Button
            tone="ghost"
            size="sm"
            icon={X}
            onClick={() => {
              setText("");
              setAssignee("");
              setType("");
            }}
          >
            Limpiar filtros
          </Button>
        )}
        {canEdit && (
          <Button tone="primary" size="sm" icon={Plus} className="ml-auto" onClick={() => setCreatingIn(project.columns[0]?.key ?? null)}>
            Crear issue
          </Button>
        )}
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
        <div className="flex flex-1 snap-x gap-2.5 overflow-x-auto px-6 pb-6">
          {project.columns.map((column, index) => (
            <Column
              key={column.key + column.name + column.category}
              column={column}
              actions={actionsFor(index)}
              blocked={dragFrom && !canMove(dragFrom, column.key) ? `No se puede pasar desde «${dragFromName}»` : undefined}
              stage={project.stages.find((s) => s.column === column.key)}
              roleName={roleName}
              projectType={project}
              issues={(columns[column.key] ?? []).map((k) => byKey.get(k)).filter(filtering ? visible : (i): i is Issue => !!i)}
              members={memberById}
              epics={epicTitles}
              plans={plans}
              working={working}
              onOpen={setOpenKey}
              onCreate={canEdit ? setCreatingIn : undefined}
            />
          ))}
          {canEdit && (
            <AddColumn
              disabled={project.columns.length >= 12}
              onAdd={(name) => saveColumns([...project.columns, { key: null, name, category: "IN_PROGRESS" }], `Columna «${name}» agregada`)}
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
          onOpenIssue={setOpenKey}
          onClose={() => setOpenKey(null)}
        />
      )}

      <SidePanel
        open={creatingIn != null}
        onClose={() => setCreatingIn(null)}
        width="narrow"
        title={`Nuevo issue en ${project.key}`}
      >
        {creatingIn && (
          <IssueForm
            key={creatingIn}
            project={project}
            initial={emptyValues(creatingIn, project)}
            members={members}
            epics={epics}
            layout="stacked"
            submitLabel="Crear issue"
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
function cardPlans(issues: Issue[], byKey: Map<string, Issue>, project: Project, roleName: (key: string) => string): Map<string, CardPlan> {
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
      const awaiting = issue.status === first && !epic.assigneeId && !!stage && !stage.gate && stage.roles.length > 0;
      plans.set(epic.key, {
        ...current,
        progress: { done: progress.done + (done.has(issue.status) ? 1 : 0), total: progress.total + 1 },
        planPending: current.planPending || awaiting,
      });
    }
  }
  return plans;
}
