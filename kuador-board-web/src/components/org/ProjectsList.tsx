"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { createSubjectAction } from "@/app/actions";
import { PageBody, PageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { Field, TextArea, TextInput } from "@/components/ui/Field";
import { SidePanel } from "@/components/ui/SidePanel";
import { notify } from "@/components/ui/Toaster";
import type { SubjectListing } from "@/lib/types";

/** Organización → Proyectos: aquello sobre lo que se trabaja, con todo lo suyo adentro. Se arma una vez. */
export function ProjectsList({ subjects, boards, creating: initial }: { subjects: SubjectListing[]; boards: { key: string; name: string }[]; creating: boolean }) {
  const [creating, setCreating] = useState(initial);
  const boardName = (key: string) => boards.find((b) => b.key === key)?.name ?? key;
  return (
    <>
      <PageHeader
        title="Proyectos"
        description="Sobre qué se trabaja: un sistema, una marca, un cliente. Su repositorio y sus documentos van adentro."
        actions={
          <Button tone="primary" icon={Plus} onClick={() => setCreating(true)}>
            Nuevo proyecto
          </Button>
        }
      />
      <PageBody>
        {subjects.length === 0 ? (
          <p className="text-[13px] text-fg-faint">Aún no hay proyectos.</p>
        ) : (
          <ul className="divide-y divide-line overflow-hidden rounded-lg border border-line bg-surface">
            {subjects.map((s) => (
              <li key={s.key}>
                <Link href={`/org/projects/${s.key}`} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3 hover:bg-hover">
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13px] font-medium text-fg-strong">{s.name}</span>
                    {s.description && <span className="line-clamp-1 text-xs text-fg-faint">{s.description}</span>}
                  </span>
                  <span className="text-xs text-fg-muted">
                    {s.boards.length === 0 ? <span className="text-fg-faint">Ningún tablero</span> : s.boards.map(boardName).join(", ")}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </PageBody>
      <SidePanel open={creating} onClose={() => setCreating(false)} width="narrow" title="Nuevo proyecto">
        {creating && <NewProject onCancel={() => setCreating(false)} />}
      </SidePanel>
    </>
  );
}

function NewProject({ onCancel }: { onCancel: () => void }) {
  const router = useRouter();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [pending, start] = useTransition();
  return (
    <form
      className="flex flex-col gap-5 px-5 py-5"
      onSubmit={(e) => {
        e.preventDefault();
        start(async () => {
          const r = await createSubjectAction({ name: name.trim(), description: description.trim() || null });
          if (!r.ok) {
            notify.error("No se pudo crear el proyecto", r.error);
            return;
          }
          router.push(`/org/projects/${r.data.key}`);
        });
      }}
    >
      <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder="Sistema de reservas" />}</Field>
      <Field label="Descripción" hint="Los agentes la leen con cada tarjeta del proyecto.">
        {(id) => <TextArea id={id} rows={4} maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button tone="ghost" onClick={onCancel}>
          Cancelar
        </Button>
        <Button type="submit" tone="primary" loading={pending} disabled={!name.trim()}>
          Crear proyecto
        </Button>
      </div>
    </form>
  );
}
