"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { updateProjectAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextArea, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { Project } from "@/lib/types";

export function ProjectDetailsForm({ project }: { project: Project }) {
  const router = useRouter();
  const [name, setName] = useState(project.name);
  const [description, setDescription] = useState(project.description ?? "");
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const dirty = name !== project.name || description !== (project.description ?? "");
  return (
    <form
      className="flex flex-col gap-4 rounded-lg border border-line bg-surface p-5"
      onSubmit={(e) => {
        e.preventDefault();
        start(async () => {
          const r = await updateProjectAction(project.key, { name, description: description.trim() || null });
          if (r.ok) {
            setError(null);
            notify.ok("Proyecto actualizado");
            router.refresh();
          } else setError(r.error);
        });
      }}
    >
      <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
      <Field label="Descripción" hint="Contexto breve para personas y agentes.">
        {(id) => <TextArea id={id} rows={3} maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      {error && <p role="alert" className="text-[13px] text-danger">{error}</p>}
      <div className="flex justify-end">
        <Button type="submit" tone="primary" size="sm" loading={pending} disabled={!dirty}>Guardar</Button>
      </div>
    </form>
  );
}
