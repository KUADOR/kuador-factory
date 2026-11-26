"use client";

import { Plus, X } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { addBoardSubjectAction, removeBoardSubjectAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { SelectInput, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { ActionResult, Subject, SubjectListing } from "@/lib/types";

/**
 * En qué proyectos trabaja este tablero (sistemas, marcas, clientes). Se suma uno que ya existe en el espacio o se crea
 * uno nuevo; la épica elige el suyo y sus tarjetas lo heredan.
 */
export function BoardSubjects({ projectKey, subjects: initial, all }: { projectKey: string; subjects: Subject[]; all: SubjectListing[] }) {
  const router = useRouter();
  const [subjects, setSubjects] = useState(initial);
  const [name, setName] = useState("");
  const [pending, start] = useTransition();
  const others = all.filter((s) => !subjects.some((mine) => mine.key === s.key));

  const act = (work: () => Promise<ActionResult<Subject[]>>, done: string) =>
    start(async () => {
      const r = await work();
      if (!r.ok) {
        notify.error("No se pudo guardar", r.error);
        return;
      }
      setSubjects(r.data);
      setName("");
      notify.ok(done);
      router.refresh();
    });

  return (
    <div className="flex flex-col gap-3">
      <ul className="flex flex-wrap gap-1.5">
        {subjects.map((s) => (
          <li key={s.key} className="inline-flex items-center gap-1 rounded-md border border-line-strong bg-raised py-1 pr-1 pl-2.5 text-[13px] text-fg-strong">
            {s.name}
            {subjects.length > 1 && (
              <button
                type="button"
                disabled={pending}
                aria-label={`Quitar ${s.name} de este tablero`}
                className="grid size-5 place-items-center rounded text-fg-muted hover:bg-hover hover:text-fg-strong"
                onClick={() => {
                  if (!window.confirm(`¿Quitar «${s.name}» de este tablero? Se van los documentos y el vínculo al repositorio que este tablero guardaba de él. El proyecto sigue en el espacio.`)) return;
                  act(() => removeBoardSubjectAction(projectKey, s.key), `${s.name} ya no está en este tablero`);
                }}
              >
                <X className="size-3.5" aria-hidden />
              </button>
            )}
          </li>
        ))}
      </ul>
      <div className="flex flex-wrap items-center gap-2">
        {others.length > 0 && (
          <div className="w-52">
            <SelectInput
              size="sm"
              ariaLabel="Sumar un proyecto del espacio"
              placeholder="Sumar un proyecto"
              disabled={pending}
              options={others.map((s) => ({ value: s.key, label: s.name }))}
              value={null}
              onChange={(v) => v && act(() => addBoardSubjectAction(projectKey, { subjectKey: v }), "Proyecto sumado al tablero")}
            />
          </div>
        )}
        <form
          className="flex items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (name.trim()) act(() => addBoardSubjectAction(projectKey, { name: name.trim() }), "Proyecto creado y sumado al tablero");
          }}
        >
          <TextInput size="sm" value={name} maxLength={120} onChange={(e) => setName(e.target.value)} placeholder="Proyecto nuevo" aria-label="Nombre del proyecto nuevo" className="w-52" />
          <Button type="submit" size="sm" icon={Plus} loading={pending} disabled={!name.trim()}>
            Crear
          </Button>
        </form>
      </div>
    </div>
  );
}
