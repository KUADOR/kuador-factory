"use client";

import { Pencil, Trash2 } from "lucide-react";
import Link from "next/link";
import { useState, useTransition } from "react";

import { deleteSubjectAction, updateSubjectAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextArea, TextInput } from "@/components/ui/Field";
import { SidePanel } from "@/components/ui/SidePanel";
import { notify } from "@/components/ui/Toaster";
import type { SubjectListing } from "@/lib/types";

/**
 * Los proyectos del espacio: aquello sobre lo que se trabaja (un sistema, una marca, un cliente). Cada tablero marca en
 * cuáles trabaja desde su configuración; aquí se les cambia el nombre, se describen (los agentes lo leen) o se borran.
 */
export function SubjectsList({ subjects, canEdit, canDelete }: { subjects: SubjectListing[]; canEdit: boolean; canDelete: boolean }) {
  const [editing, setEditing] = useState<SubjectListing | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [pending, start] = useTransition();
  const open = (s: SubjectListing) => {
    setEditing(s);
    setName(s.name);
    setDescription(s.description ?? "");
  };
  return (
    <section className="mt-8 flex flex-col gap-3">
      <header>
        <h2 className="text-base font-semibold text-fg-strong">Proyectos</h2>
        <p className="mt-1 text-[13px] text-fg-muted">Aquello sobre lo que trabajan los tableros.</p>
      </header>
      <ul className="divide-y divide-line overflow-hidden rounded-lg border border-line bg-surface">
        {subjects.map((s) => (
          <li key={s.key} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3">
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[13px] font-medium text-fg-strong">{s.name}</span>
              {s.description && <span className="line-clamp-1 text-xs text-fg-faint">{s.description}</span>}
            </span>
            <span className="flex flex-wrap items-center gap-1.5 text-xs text-fg-muted">
              {s.boards.length === 0 && <span className="text-fg-faint">Ningún tablero</span>}
              {s.boards.map((b) => (
                <Link key={b} href={`/projects/${b}`} className="rounded bg-sunken px-1.5 py-0.5 font-mono text-[11px] text-fg-strong hover:bg-hover">
                  {b}
                </Link>
              ))}
            </span>
            {canEdit && <Button tone="ghost" size="sm" icon={Pencil} iconOnly label={`Editar ${s.name}`} onClick={() => open(s)} />}
            {canDelete && s.boards.length === 0 && (
              <Button
                tone="ghost"
                size="sm"
                icon={Trash2}
                iconOnly
                label={`Borrar ${s.name}`}
                disabled={pending}
                onClick={() => {
                  if (!window.confirm(`¿Borrar el proyecto «${s.name}»? Ningún tablero trabaja en él.`)) return;
                  start(async () => {
                    const r = await deleteSubjectAction(s.key);
                    if (r.ok) notify.ok("Proyecto borrado");
                    else notify.error("No se pudo borrar", r.error);
                  });
                }}
              />
            )}
          </li>
        ))}
      </ul>
      <SidePanel open={editing != null} onClose={() => setEditing(null)} width="narrow" title="Proyecto">
        {editing && (
          <form
            className="flex flex-col gap-5 px-5 py-5"
            onSubmit={(e) => {
              e.preventDefault();
              start(async () => {
                const r = await updateSubjectAction(editing.key, { name: name.trim(), description: description.trim() || null });
                if (!r.ok) {
                  notify.error("No se pudo guardar", r.error);
                  return;
                }
                notify.ok("Proyecto guardado");
                setEditing(null);
              });
            }}
          >
            <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
            <Field label="Descripción" hint="Los agentes la leen con cada tarjeta del proyecto.">
              {(id) => <TextArea id={id} rows={4} maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} />}
            </Field>
            <div className="flex justify-end gap-2 border-t border-line pt-4">
              <Button tone="ghost" onClick={() => setEditing(null)}>
                Cancelar
              </Button>
              <Button type="submit" tone="primary" loading={pending}>
                Guardar
              </Button>
            </div>
          </form>
        )}
      </SidePanel>
    </section>
  );
}
