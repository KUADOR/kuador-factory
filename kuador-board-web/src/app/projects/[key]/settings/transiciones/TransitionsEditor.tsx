"use client";

import { useRouter } from "next/navigation";
import { Check, MousePointerClick, Plus, TriangleAlert, X } from "lucide-react";
import { clsx } from "clsx";
import { useState, useTransition } from "react";

import { setTransitionsAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { SelectInput, Switch } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { BoardColumn, Project } from "@/lib/types";

const CATEGORY_DOT = { TODO: "bg-fg-faint", IN_PROGRESS: "bg-info", DONE: "bg-ok" } as const;
const CATEGORY_FILL = { TODO: "var(--k-fg-faint)", IN_PROGRESS: "var(--k-info)", DONE: "var(--k-ok)" } as const;
const key = (from: string, to: string) => `${from}>${to}`;

/**
 * Reglas de transición como un flujo: las columnas son etapas en orden y cada regla es una flecha
 * (arriba las que avanzan, abajo las que regresan). Se edita sobre el diagrama —clic en una etapa
 * y luego en sus destinos— o en la lista «Desde… puede pasar a…». La API las aplica a todos.
 */
export function TransitionsEditor({ project }: { project: Project }) {
  const router = useRouter();
  const cols = project.columns;
  const [restricted, setRestricted] = useState(project.transitions.restricted);
  const [allowed, setAllowed] = useState(() => new Set(project.transitions.allowed.map((t) => key(t.from, t.to))));
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  const toggle = (from: string, to: string) =>
    setAllowed((prev) => {
      const next = new Set(prev);
      if (next.has(key(from, to))) next.delete(key(from, to));
      else next.add(key(from, to));
      return next;
    });
  const preset = (kind: "steps" | "forward" | "all" | "none") => {
    setSelected(null);
    setAllowed(
      new Set(
        cols.flatMap((a, i) =>
          cols.flatMap((b, j) => {
            if (i === j || kind === "none") return [];
            if (kind === "all") return [key(a.key, b.key)];
            if (kind === "forward") return j === i + 1 ? [key(a.key, b.key)] : [];
            return Math.abs(i - j) === 1 ? [key(a.key, b.key)] : [];
          }),
        ),
      ),
    );
  };
  const targetsOf = (from: string) => cols.filter((c) => c.key !== from && allowed.has(key(from, c.key)));
  const unreachable = cols.slice(1).filter((c) => !cols.some((f) => allowed.has(key(f.key, c.key))));
  const deadEnds = cols.filter((c) => c.category !== "DONE" && targetsOf(c.key).length === 0);
  const selectedColumn = cols.find((c) => c.key === selected);

  const save = () =>
    start(async () => {
      const list = [...allowed].map((k) => {
        const [from, to] = k.split(">");
        return { from, to };
      });
      const r = await setTransitionsAction(project.key, { restricted, allowed: restricted ? list : [] });
      if (r.ok) {
        setError(null);
        notify.ok(restricted ? "Flujo de trabajo guardado" : "Movimiento libre entre columnas");
        router.refresh();
      } else setError(r.error);
    });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1 rounded-lg border border-line bg-surface px-5 py-4">
        <Switch checked={restricted} onChange={setRestricted} label="Fijar el flujo de trabajo de este proyecto" />
        <p className="text-xs text-fg-muted">
          {restricted
            ? "Solo se permiten los pasos que dibujes. Valen para personas y agentes."
            : "Ahora cualquier issue puede ir a cualquier columna. Actívalo para definir qué pasos están permitidos."}
        </p>
      </div>

      {restricted && (
        <>
          <div className="flex flex-col gap-3 rounded-lg border border-line bg-surface">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-3">
              <p className="flex items-center gap-2 text-[13px] text-fg">
                <MousePointerClick className="size-4 shrink-0 text-fg-muted" aria-hidden />
                {selectedColumn ? (
                  <span>
                    Desde <strong className="font-semibold text-fg-strong">{selectedColumn.name}</strong>: haz clic en las etapas a las que puede pasar. Clic otra vez para quitar.
                  </span>
                ) : (
                  <span>Haz clic en una etapa para elegir a dónde puede pasar un issue desde ahí.</span>
                )}
              </p>
              <div className="flex flex-wrap items-center gap-1">
                <span className="mr-1 text-xs text-fg-faint">Empezar con:</span>
                <Button size="sm" tone="ghost" onClick={() => preset("steps")}>Paso a paso</Button>
                <Button size="sm" tone="ghost" onClick={() => preset("forward")}>Solo avanzar</Button>
                <Button size="sm" tone="ghost" onClick={() => preset("all")}>Libre</Button>
                <Button size="sm" tone="ghost" onClick={() => preset("none")}>Vacío</Button>
              </div>
            </div>
            <FlowDiagram
              columns={cols}
              allowed={allowed}
              selected={selected}
              unreachable={new Set(unreachable.map((c) => c.key))}
              onNode={(k) => {
                if (!selected || selected === k) setSelected(selected === k ? null : k);
                else toggle(selected, k);
              }}
            />
            <div className="flex flex-wrap gap-x-5 gap-y-1 border-t border-line px-5 py-2.5 text-xs text-fg-muted">
              <span className="flex items-center gap-1.5">
                <span aria-hidden className="h-0.5 w-5 rounded bg-fg-muted" /> Arriba: avanza
              </span>
              <span className="flex items-center gap-1.5">
                <span aria-hidden className="h-0.5 w-5 rounded border-t-2 border-dashed border-fg-muted" /> Abajo: regresa
              </span>
              <span className="flex items-center gap-1.5">
                <span aria-hidden className="h-0.5 w-5 rounded bg-sol" /> Desde la etapa elegida
              </span>
            </div>
          </div>

          <ul className="flex flex-col divide-y divide-line rounded-lg border border-line bg-surface">
            {cols.map((from) => {
              const targets = targetsOf(from.key);
              const options = cols.filter((c) => c.key !== from.key && !allowed.has(key(from.key, c.key)));
              return (
                <li key={from.key} className={clsx("grid gap-3 px-5 py-3 sm:grid-cols-[12rem_minmax(0,1fr)]", selected === from.key && "bg-sol/5")}>
                  <button type="button" onClick={() => setSelected(selected === from.key ? null : from.key)} className="flex items-center gap-2 self-start text-left text-[13px] font-medium text-fg-strong">
                    <span aria-hidden className={`size-2 shrink-0 rounded-full ${CATEGORY_DOT[from.category]}`} />
                    Desde {from.name}
                  </button>
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-xs text-fg-muted">puede pasar a</span>
                    {targets.length === 0 && <span className="text-xs text-fg-faint italic">{from.category === "DONE" ? "ninguna (es una etapa final)" : "ninguna todavía"}</span>}
                    {targets.map((t) => (
                      <span key={t.key} className="inline-flex items-center gap-1 rounded-md border border-line-strong bg-raised py-0.5 pr-0.5 pl-2 text-xs text-fg-strong">
                        {t.name}
                        <button type="button" onClick={() => toggle(from.key, t.key)} aria-label={`Quitar el paso de ${from.name} a ${t.name}`} className="grid size-5 place-items-center rounded text-fg-muted hover:bg-hover hover:text-fg-strong">
                          <X className="size-3" aria-hidden />
                        </button>
                      </span>
                    ))}
                    {options.length > 0 && (
                      <div className="w-44">
                        <SelectInput
                          size="sm"
                          ariaLabel={`Agregar destino desde ${from.name}`}
                          placeholder="Agregar destino…"
                          options={options.map((c) => ({ value: c.key, label: c.name, leading: <Plus className="size-3.5 text-fg-faint" aria-hidden /> }))}
                          value={null}
                          onChange={(to) => to && toggle(from.key, to)}
                        />
                      </div>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>

          {(unreachable.length > 0 || deadEnds.length > 0) && (
            <ul className="flex flex-col gap-1.5">
              {unreachable.map((c) => (
                <li key={`u-${c.key}`} className="flex items-center gap-2 text-[13px] text-danger">
                  <TriangleAlert className="size-4 shrink-0" aria-hidden /> A «{c.name}» no se llega desde ninguna etapa: agrégale al menos una entrada para poder guardar.
                </li>
              ))}
              {deadEnds.map((c) => (
                <li key={`d-${c.key}`} className="flex items-center gap-2 text-[13px] text-warn">
                  <TriangleAlert className="size-4 shrink-0" aria-hidden /> De «{c.name}» no se puede salir: los issues que lleguen ahí quedarán detenidos.
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {error && <p role="alert" className="text-[13px] text-danger">{error}</p>}
      <div className="flex justify-end">
        <Button tone="primary" size="sm" loading={pending} disabled={restricted && unreachable.length > 0} onClick={save}>
          Guardar flujo
        </Button>
      </div>
    </div>
  );
}

// ---- Diagrama ----

const NODE_W = 136;
const NODE_H = 44;
const GAP = 48;
const ARC_BASE = 26;
const ARC_STEP = 20;

/** El flujo dibujado: etapas en fila; flechas arriba (avanzan) y abajo (regresan), más altas cuanto más lejos saltan. */
function FlowDiagram({
  columns,
  allowed,
  selected,
  unreachable,
  onNode,
}: {
  columns: BoardColumn[];
  allowed: Set<string>;
  selected: string | null;
  unreachable: Set<string>;
  onNode: (key: string) => void;
}) {
  const index = new Map(columns.map((c, i) => [c.key, i]));
  const edges = [...allowed].flatMap((k) => {
    const [from, to] = k.split(">");
    const i = index.get(from);
    const j = index.get(to);
    return i == null || j == null ? [] : [{ from, to, i, j }];
  });
  const maxSpan = Math.max(1, ...edges.map((e) => Math.abs(e.j - e.i)));
  const lift = ARC_BASE + ARC_STEP * maxSpan;
  const width = columns.length * NODE_W + (columns.length - 1) * GAP + 32;
  const top = lift + 12;
  const height = top + NODE_H + lift + 12;
  const cx = (i: number) => 16 + i * (NODE_W + GAP) + NODE_W / 2;

  const arc = (i: number, j: number) => {
    const forward = j > i;
    const span = Math.abs(j - i);
    const h = ARC_BASE + ARC_STEP * span;
    // Avanzar sale por arriba un poco a la derecha; regresar, por abajo un poco a la izquierda: nunca se pisan.
    const x1 = cx(i) + (forward ? 18 : -18);
    const x2 = cx(j) + (forward ? -18 : 18);
    const y = forward ? top : top + NODE_H;
    const cy = forward ? y - h : y + h;
    return { d: `M ${x1} ${y} C ${x1} ${cy}, ${x2} ${cy}, ${x2} ${y}`, forward };
  };

  return (
    <div className="overflow-x-auto px-3 pb-1">
      {/* Se escala al ancho disponible (todas las etapas a la vista); por debajo de ~70 % se desplaza. */}
      <svg
        viewBox={`0 0 ${width} ${height}`}
        role="group"
        aria-label="Diagrama del flujo de trabajo"
        className="block h-auto w-full"
        style={{ minWidth: Math.round(width * 0.7), maxWidth: width }}
      >
        <defs>
          {(["muted", "active"] as const).map((tone) => (
            <marker key={tone} id={`arrow-${tone}`} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" fill={tone === "active" ? "var(--k-sol)" : "var(--k-line-hover)"} />
            </marker>
          ))}
        </defs>

        {edges
          .sort((a, b) => Number(a.from === selected) - Number(b.from === selected))
          .map((e) => {
            const { d, forward } = arc(e.i, e.j);
            const active = e.from === selected;
            return (
              <path
                key={`${e.from}>${e.to}`}
                d={d}
                fill="none"
                stroke={active ? "var(--k-sol)" : "var(--k-line-hover)"}
                strokeWidth={active ? 2.25 : 1.5}
                strokeDasharray={forward ? undefined : "5 4"}
                markerEnd={`url(#arrow-${active ? "active" : "muted"})`}
                opacity={selected && !active ? 0.45 : 1}
              >
                <title>{`${columns[e.i].name} → ${columns[e.j].name}`}</title>
              </path>
            );
          })}

        {columns.map((c, i) => {
          const x = cx(i) - NODE_W / 2;
          const isSelected = c.key === selected;
          const isTarget = !!selected && allowed.has(`${selected}>${c.key}`);
          const canPick = !!selected && !isSelected;
          return (
            <g
              key={c.key}
              role="button"
              tabIndex={0}
              aria-pressed={isSelected || isTarget}
              aria-label={selected && !isSelected ? `${isTarget ? "Quitar" : "Permitir"} el paso de ${columns.find((x) => x.key === selected)?.name} a ${c.name}` : `Elegir ${c.name} como origen`}
              onClick={() => onNode(c.key)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  onNode(c.key);
                }
              }}
              className="cursor-pointer outline-none [&:focus-visible>rect]:stroke-[var(--k-sol)]"
            >
              <rect
                x={x}
                y={top}
                width={NODE_W}
                height={NODE_H}
                rx={8}
                fill={isSelected ? "color-mix(in srgb, var(--k-sol) 14%, var(--k-raised))" : "var(--k-raised)"}
                stroke={isSelected || isTarget ? "var(--k-sol)" : unreachable.has(c.key) ? "var(--k-danger)" : "var(--k-line-strong)"}
                strokeWidth={isSelected ? 2 : 1.25}
                strokeDasharray={canPick && !isTarget ? "4 3" : undefined}
              />
              <circle cx={x + 16} cy={top + NODE_H / 2} r={4} fill={CATEGORY_FILL[c.category]} />
              <text x={x + 28} y={top + NODE_H / 2 + 4} fontSize={13} fontWeight={isSelected ? 600 : 500} fill="var(--k-fg-strong)">
                {c.name.length > 16 ? `${c.name.slice(0, 15)}…` : c.name}
              </text>
              {canPick &&
                (isTarget ? (
                  <Check x={x + NODE_W - 24} y={top + NODE_H / 2 - 8} width={16} height={16} color="var(--k-sol-text)" strokeWidth={2.5} aria-hidden />
                ) : (
                  <Plus x={x + NODE_W - 24} y={top + NODE_H / 2 - 8} width={16} height={16} color="var(--k-fg-faint)" aria-hidden />
                ))}
            </g>
          );
        })}
      </svg>
    </div>
  );
}
