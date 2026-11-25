"use client";

import { ArrowDown, ArrowUp, Plus, Trash } from "lucide-react";
import { useState, useTransition } from "react";

import { updateColumnsAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { SelectInput, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import { CATEGORY_LABEL } from "@/lib/labels";
import { COLUMN_CATEGORIES, type BoardColumn, type ColumnCategory, type Project } from "@/lib/types";

type Draft = { id: string; key: string | null; name: string; category: ColumnCategory };

let nextId = 0;
const toDraft = (c: BoardColumn): Draft => ({ id: `c${nextId++}`, ...c });
const CATEGORY_DOT = { TODO: "bg-fg-faint", IN_PROGRESS: "bg-info", DONE: "bg-ok" } as const;
const categoryOptions = COLUMN_CATEGORIES.map((c) => ({ value: c, label: CATEGORY_LABEL[c], leading: <span aria-hidden className={`size-2 rounded-full ${CATEGORY_DOT[c]}`} /> }));

/** Los pasos del tablero: se agregan, renombran, ordenan y quitan, y se guardan de una vez para que lleguen juntos. */
export function StepsEditor({ project, counts, onSaved }: { project: Project; counts: Record<string, number>; onSaved: () => void }) {
  const [drafts, setDrafts] = useState(() => project.columns.map(toDraft));
  const [pending, startTransition] = useTransition();

  const update = (id: string, patch: Partial<Draft>) => setDrafts((d) => d.map((c) => (c.id === id ? { ...c, ...patch } : c)));
  const move = (index: number, delta: number) =>
    setDrafts((d) => {
      const next = [...d];
      const [item] = next.splice(index, 1);
      next.splice(index + delta, 0, item);
      return next;
    });

  return (
    <form
      className="flex flex-col gap-3 p-5"
      onSubmit={(e) => {
        e.preventDefault();
        startTransition(async () => {
          const result = await updateColumnsAction(project.key, drafts.map(({ key, name, category }) => ({ key, name, category })));
          if (result.ok) {
            setDrafts(result.data.columns.map(toDraft));
            notify.ok("Pasos guardados");
            onSaved();
          } else notify.error("No se pudieron guardar los pasos", result.error);
        });
      }}
    >
      <ol className="flex flex-col divide-y divide-line rounded-md border border-line">
        {drafts.map((c, i) => {
          const count = c.key ? (counts[c.key] ?? 0) : 0;
          return (
            <li key={c.id} className="grid grid-cols-[1.25rem_minmax(7rem,1fr)_9.5rem_auto] items-center gap-2 px-2 py-1.5">
              <span className="tabular text-center font-mono text-xs text-fg-faint">{i + 1}</span>
              <TextInput size="sm" required maxLength={40} value={c.name} onChange={(e) => update(c.id, { name: e.target.value })} aria-label={`Nombre del paso ${i + 1}`} />
              <SelectInput size="sm" ariaLabel={`Categoría de ${c.name || `el paso ${i + 1}`}`} options={categoryOptions} value={c.category} onChange={(v) => v && update(c.id, { category: v })} />
              <span className="flex">
                <Button tone="ghost" size="sm" icon={ArrowUp} iconOnly label={`Subir ${c.name}`} disabled={i === 0} onClick={() => move(i, -1)} />
                <Button tone="ghost" size="sm" icon={ArrowDown} iconOnly label={`Bajar ${c.name}`} disabled={i === drafts.length - 1} onClick={() => move(i, 1)} />
                <Button
                  tone="ghost"
                  size="sm"
                  icon={Trash}
                  iconOnly
                  label={count > 0 ? `Tiene ${count} tarjeta(s): muévelas primero a otro paso` : `Quitar ${c.name}`}
                  disabled={drafts.length === 1 || count > 0}
                  onClick={() => setDrafts((d) => d.filter((x) => x.id !== c.id))}
                />
              </span>
            </li>
          );
        })}
      </ol>
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" icon={Plus} disabled={drafts.length >= 12} onClick={() => setDrafts((d) => [...d, { id: `c${nextId++}`, key: null, name: "", category: "IN_PROGRESS" }])}>
          Agregar paso
        </Button>
        <Button type="submit" tone="primary" size="sm" loading={pending} className="ml-auto">
          Guardar pasos
        </Button>
      </div>
    </form>
  );
}
