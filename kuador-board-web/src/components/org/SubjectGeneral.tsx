"use client";

import { Trash2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { deleteSubjectAction, updateSubjectAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextArea, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { SubjectListing } from "@/lib/types";

/** Nombre y descripción del proyecto, quién lo trabaja y, si nadie, borrarlo. */
export function SubjectGeneral({ subject, boards }: { subject: SubjectListing; boards: { key: string; name: string }[] }) {
  const router = useRouter();
  const [name, setName] = useState(subject.name);
  const [description, setDescription] = useState(subject.description ?? "");
  const [pending, start] = useTransition();
  const dirty = name.trim() !== subject.name || (description.trim() || null) !== subject.description;
  return (
    <section className="flex flex-col gap-4">
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          start(async () => {
            const r = await updateSubjectAction(subject.key, { name: name.trim(), description: description.trim() || null });
            if (!r.ok) {
              notify.error("No se pudo guardar", r.error);
              return;
            }
            notify.ok("Proyecto guardado");
            router.refresh();
          });
        }}
      >
        <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
        <Field label="Descripción" hint="Los agentes la leen con cada tarjeta del proyecto.">
          {(id) => <TextArea id={id} rows={3} maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} />}
        </Field>
        {dirty && (
          <div className="flex justify-end">
            <Button type="submit" tone="primary" size="sm" loading={pending} disabled={!name.trim()}>
              Guardar
            </Button>
          </div>
        )}
      </form>
      <p className="text-[13px] text-fg-muted">
        {boards.length === 0 ? (
          "Ningún tablero lo trabaja todavía."
        ) : (
          <>
            Lo trabajan:{" "}
            {boards.map((b, i) => (
              <span key={b.key}>
                {i > 0 && ", "}
                <Link href={`/projects/${b.key}`} className="k-link">
                  {b.name}
                </Link>
              </span>
            ))}
          </>
        )}
      </p>
      {boards.length === 0 && (
        <div>
          <Button
            tone="danger"
            size="sm"
            icon={Trash2}
            disabled={pending}
            onClick={() => {
              if (!window.confirm(`¿Borrar el proyecto «${subject.name}» con su repositorio vinculado y sus documentos?`)) return;
              start(async () => {
                const r = await deleteSubjectAction(subject.key);
                if (!r.ok) {
                  notify.error("No se pudo borrar", r.error);
                  return;
                }
                notify.ok("Proyecto borrado");
                router.push("/org/projects");
              });
            }}
          >
            Borrar proyecto
          </Button>
        </div>
      )}
    </section>
  );
}
