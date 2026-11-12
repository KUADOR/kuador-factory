"use client";

import { Trash2 } from "lucide-react";
import { useState, useTransition } from "react";

import { deleteProjectAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Field, TextInput } from "@/components/ui/Field";

/**
 * Borrar el tablero con todo su contenido. Pide escribir la clave para que no se borre uno por error; los
 * repositorios en GitHub no se tocan.
 */
export function DeleteProject({ projectKey, projectName, issues, software }: { projectKey: string; projectName: string; issues: number; software: boolean }) {
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const close = () => {
    setOpen(false);
    setTyped("");
    setError(null);
  };

  return (
    <section className="rounded-lg border border-danger/40 bg-surface">
      <div className="flex flex-wrap items-center gap-4 px-5 py-4">
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold text-fg-strong">Eliminar tablero</h2>
          <p className="mt-0.5 max-w-2xl text-[13px] leading-relaxed text-fg-muted">
            Borra {projectName} con {issues === 1 ? "su tarjeta" : `sus ${issues} tarjetas`} y sus comentarios, adjuntos, documentos e historial. No se puede deshacer.
          </p>
        </div>
        <Button tone="danger" icon={Trash2} onClick={() => setOpen(true)}>
          Eliminar tablero
        </Button>
      </div>

      <Dialog
        open={open}
        onClose={close}
        title={`Eliminar ${projectName}`}
        footer={
          <>
            <Button tone="ghost" onClick={close}>
              Cancelar
            </Button>
            <Button
              tone="danger"
              loading={pending}
              disabled={typed.trim().toUpperCase() !== projectKey}
              onClick={() =>
                start(async () => {
                  const r = await deleteProjectAction(projectKey, typed.trim());
                  if (r && !r.ok) setError(r.error);
                })
              }
            >
              Eliminar para siempre
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <p>
            Se borran el tablero y todo lo que tiene: {issues} {issues === 1 ? "tarjeta" : "tarjetas"}, sus comentarios, adjuntos, documentos,
            historial y corridas de agentes. No se puede deshacer.
            {software && " Los repositorios en GitHub no se tocan."}
          </p>
          <Field label={`Escribe ${projectKey} para confirmar`} error={error}>
            {(id) => <TextInput id={id} autoFocus autoComplete="off" value={typed} onChange={(e) => setTyped(e.target.value)} className="font-mono uppercase" />}
          </Field>
        </div>
      </Dialog>
    </section>
  );
}
