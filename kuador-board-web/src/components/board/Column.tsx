"use client";

import { useDroppable } from "@dnd-kit/core";
import { SortableContext, useSortable, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { ArrowLeft, ArrowRight, Ban, CircleCheck, CircleDashed, CircleDot, Ellipsis, Lock, Pencil, Plus, Trash } from "lucide-react";
import { clsx } from "clsx";
import { useState } from "react";

import { Button } from "@/components/ui/Button";
import { DropdownMenu } from "@/components/ui/DropdownMenu";
import { TextInput } from "@/components/ui/Field";
import type { TypeSource } from "@/lib/labels";
import type { BoardColumn, CardPlan, ColumnCategory, Issue, IssueStatus, Member, StageDef } from "@/lib/types";

import { COLUMN_DROP_PREFIX } from "./dnd";
import { IssueCard } from "./IssueCard";
import { roleList } from "./stages";

const ACCENT: Record<ColumnCategory, string> = {
  TODO: "bg-fg-faint",
  IN_PROGRESS: "bg-info",
  DONE: "bg-ok",
};

/** Cambios de la columna que el tablero guarda en el flujo del proyecto. */
export interface ColumnActions {
  rename: (name: string) => void;
  setCategory: (category: ColumnCategory) => void;
  move: (delta: -1 | 1) => void;
  remove: () => void;
  canMoveLeft: boolean;
  canMoveRight: boolean;
  canRemove: boolean;
}

function SortableIssue({
  issue,
  assignee,
  epicTitle,
  projectType,
  workingAgent,
  waiting,
  plan,
  readOnly,
  onOpen,
}: {
  issue: Issue;
  assignee: Member | undefined;
  epicTitle: string | undefined;
  projectType: TypeSource;
  workingAgent: Member | undefined;
  waiting?: string;
  plan?: CardPlan;
  readOnly?: boolean;
  onOpen: (key: string) => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: issue.key, disabled: readOnly });
  return (
    <li ref={setNodeRef} style={{ transform: CSS.Translate.toString(transform), transition }} className={isDragging ? "opacity-30" : undefined}>
      {/* dnd-kit aporta role="button" y tabIndex. Enter abre el detalle; Espacio arrastra con teclado. */}
      <div
        {...attributes}
        {...listeners}
        // En solo lectura no se arrastra, pero la tarjeta sigue abriendo el detalle: no está «deshabilitada».
        aria-disabled={readOnly ? undefined : attributes["aria-disabled"]}
        aria-describedby={readOnly ? undefined : attributes["aria-describedby"]}
        onClick={() => onOpen(issue.key)}
        onKeyDown={(e) => {
          if (e.key === "Enter") onOpen(issue.key);
          else listeners?.onKeyDown?.(e);
        }}
        aria-label={`${issue.key}: ${issue.title}`}
        aria-roledescription={readOnly ? undefined : "issue arrastrable"}
        className={clsx("block w-full touch-manipulation rounded-md", readOnly ? "cursor-pointer" : "cursor-grab active:cursor-grabbing")}
      >
        <IssueCard issue={issue} assignee={assignee} epicTitle={epicTitle} projectType={projectType} workingAgent={workingAgent} waiting={waiting} plan={plan} />
      </div>
    </li>
  );
}

export function Column({
  column,
  projectType,
  issues,
  members,
  epics,
  plans,
  working,
  blocked,
  stage,
  roleName,
  actions,
  onOpen,
  onCreate,
}: {
  column: BoardColumn;
  projectType: TypeSource;
  issues: Issue[];
  members: Map<string, Member>;
  epics: Map<string, string>;
  /** issueKey → plan de la épica que muestra la tarjeta (rol, a qué espera, avance). */
  plans?: Map<string, CardPlan>;
  /** issueKey → agente que lo está trabajando ahora. */
  working: Map<string, Member>;
  /** Durante un arrastre: el flujo no permite soltar aquí (texto con el motivo). */
  blocked?: string;
  /** Roles que cubren la columna y si es puerta (Fase 1). */
  stage?: StageDef;
  roleName: (key: string) => string;
  actions: ColumnActions;
  onOpen: (key: string) => void;
  /** Sin esta función (solo lectura) la columna no ofrece crear, editar ni arrastrar. */
  onCreate?: (status: IssueStatus) => void;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: COLUMN_DROP_PREFIX + column.key, disabled: !!blocked });
  const points = issues.reduce((sum, i) => sum + (i.storyPoints ?? 0), 0);
  const [renaming, setRenaming] = useState(false);
  const [name, setName] = useState(column.name);

  const finishRename = () => {
    setRenaming(false);
    const next = name.trim();
    if (next && next !== column.name) actions.rename(next);
    else setName(column.name);
  };

  return (
    <section
      aria-label={column.name}
      data-column={column.key}
      className={clsx(
        "flex max-h-full min-h-0 w-[min(18rem,85vw)] shrink-0 snap-start flex-col rounded-lg border bg-sunken transition-[opacity,border-color]",
        blocked ? "border-dashed border-line-strong opacity-45" : isOver ? "border-sol/70" : "border-transparent",
      )}
    >
      <header className="flex h-10 items-center gap-2 pr-1 pl-3">
        <span aria-hidden className={`size-2 shrink-0 rounded-full ${ACCENT[column.category]}`} />
        {renaming ? (
          <TextInput
            size="sm"
            autoFocus
            maxLength={40}
            value={name}
            aria-label="Nombre de la columna"
            onChange={(e) => setName(e.target.value)}
            onBlur={finishRename}
            onKeyDown={(e) => {
              if (e.key === "Enter") finishRename();
              if (e.key === "Escape") {
                setName(column.name);
                setRenaming(false);
              }
            }}
          />
        ) : (
          <h2 className="truncate text-xs font-semibold text-fg-strong" onDoubleClick={onCreate ? () => setRenaming(true) : undefined} title={onCreate ? "Doble clic para renombrar" : undefined}>
            {column.name}
          </h2>
        )}
        {!renaming && (
          <span className="tabular font-mono text-[11px] text-fg-faint">
            {issues.length}
            {points > 0 && <span title="Story points de la columna"> · {points} pts</span>}
          </span>
        )}
        {onCreate && (
        <span className="ml-auto flex shrink-0">
          <button
            type="button"
            onClick={() => onCreate(column.key)}
            aria-label={`Crear en ${column.name}`}
            title={`Crear en ${column.name}`}
            className="grid size-7 place-items-center rounded-md text-fg-muted hover:bg-hover hover:text-fg-strong"
          >
            <Plus className="size-4" aria-hidden />
          </button>
          <DropdownMenu
            trigger={<Button tone="ghost" size="sm" icon={Ellipsis} iconOnly label={`Opciones de ${column.name}`} />}
            items={[
              { label: "Renombrar", icon: Pencil, onSelect: () => setRenaming(true) },
              "separator",
              { label: "Por empezar", icon: CircleDashed, disabled: column.category === "TODO", onSelect: () => actions.setCategory("TODO") },
              { label: "En curso", icon: CircleDot, disabled: column.category === "IN_PROGRESS", onSelect: () => actions.setCategory("IN_PROGRESS") },
              { label: "Terminado", icon: CircleCheck, disabled: column.category === "DONE", onSelect: () => actions.setCategory("DONE") },
              "separator",
              { label: "Mover a la izquierda", icon: ArrowLeft, disabled: !actions.canMoveLeft, onSelect: () => actions.move(-1) },
              { label: "Mover a la derecha", icon: ArrowRight, disabled: !actions.canMoveRight, onSelect: () => actions.move(1) },
              "separator",
              {
                label: issues.length > 0 ? "Quitar (mueve antes sus issues)" : "Quitar columna",
                icon: Trash,
                danger: true,
                disabled: !actions.canRemove || issues.length > 0,
                onSelect: actions.remove,
              },
            ]}
          />
        </span>
        )}
      </header>
      {stage && (stage.gate || stage.roles.length > 0) && (
        <p className="-mt-1.5 flex items-center gap-1.5 truncate px-3 pb-2 text-[11px] text-fg-muted">
          {stage.gate ? (
            <>
              <Lock className="size-3 shrink-0 text-sol-text" aria-hidden />
              <span className="truncate">Aprueba {roleList(stage.approvers, roleName, "una persona")}</span>
            </>
          ) : (
            <span className="truncate">{roleList(stage.roles, roleName)}</span>
          )}
        </p>
      )}
      {blocked && (
        <p className="mx-2 mb-1.5 flex items-center gap-1.5 rounded bg-hover px-2 py-1 text-[11px] text-fg-muted">
          <Ban className="size-3 shrink-0" aria-hidden /> {blocked}
        </p>
      )}
      <SortableContext id={column.key} items={issues.map((i) => i.key)} strategy={verticalListSortingStrategy}>
        <ul ref={setNodeRef} className="flex min-h-24 flex-1 flex-col gap-1.5 overflow-y-auto px-1.5 pb-2">
          {issues.map((issue) => (
            <SortableIssue
              key={issue.key}
              issue={issue}
              assignee={issue.assigneeId ? members.get(issue.assigneeId) : undefined}
              epicTitle={issue.parentKey ? epics.get(issue.parentKey) : undefined}
              projectType={projectType}
              workingAgent={working.get(issue.key)}
              plan={plans?.get(issue.key)}
              readOnly={!onCreate}
              waiting={!issue.assigneeId && stage && (stage.gate || stage.roles.length > 0) ? (stage.gate && stage.roles.length === 0 ? "Por aprobar" : "Por tomar") : undefined}
              onOpen={onOpen}
            />
          ))}
          {issues.length === 0 && (
            <li className="rounded-md border border-dashed border-line px-3 py-3 text-center text-xs text-fg-faint">Sin issues</li>
          )}
        </ul>
      </SortableContext>
    </section>
  );
}

/** Columna nueva al final del tablero: nombre y Enter. */
export function AddColumn({ disabled, onAdd }: { disabled: boolean; onAdd: (name: string) => void }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  if (!open) {
    return (
      <button
        type="button"
        disabled={disabled}
        onClick={() => setOpen(true)}
        title={disabled ? "Máximo 12 columnas" : undefined}
        className="flex h-10 w-48 shrink-0 items-center gap-2 rounded-lg border border-dashed border-line-strong px-3 text-xs text-fg-muted hover:border-line-hover hover:text-fg-strong disabled:opacity-50"
      >
        <Plus className="size-4" aria-hidden /> Agregar columna
      </button>
    );
  }
  return (
    <form
      className="flex w-[min(18rem,85vw)] shrink-0 flex-col gap-2 self-start rounded-lg border border-line-strong bg-sunken p-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (!name.trim()) return;
        onAdd(name.trim());
        setName("");
        setOpen(false);
      }}
    >
      <TextInput size="sm" autoFocus required maxLength={40} value={name} onChange={(e) => setName(e.target.value)} placeholder="Nombre, ej. En revisión" aria-label="Nombre de la columna nueva" onKeyDown={(e) => e.key === "Escape" && setOpen(false)} />
      <div className="flex justify-end gap-1">
        <Button size="sm" tone="ghost" onClick={() => setOpen(false)}>Cancelar</Button>
        <Button size="sm" tone="primary" type="submit">Agregar</Button>
      </div>
    </form>
  );
}
