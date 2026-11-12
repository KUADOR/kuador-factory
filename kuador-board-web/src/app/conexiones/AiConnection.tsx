"use client";

import { useState, useTransition } from "react";

import { removeWorkspaceAiAction, saveWorkspaceAiAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { Field, PasswordInput, SelectInput } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { WorkspaceAiStatus } from "@/lib/types";

const MODEL_LABEL: Record<string, string> = {
  "claude-opus-5-5": "Claude Opus 5.5 (el más capaz)",
  "claude-sonnet-5-5": "Claude Sonnet 5.5 (equilibrado)",
  "claude-haiku-5-5": "Claude Haiku 5.5 (rápido y económico)",
};

/**
 * Clave de IA de la empresa: con ella trabajan los agentes «en el tablero». Se comprueba con Anthropic, se guarda
 * cifrada y nunca se vuelve a mostrar. El consumo se cobra en la cuenta de Anthropic de la empresa.
 */
export function AiConnection({ status: initial }: { status: WorkspaceAiStatus }) {
  const [status, setStatus] = useState(initial);
  const [key, setKey] = useState("");
  const [model, setModel] = useState(initial.defaultModel);
  const [editing, setEditing] = useState(!initial.configured);
  const [confirm, setConfirm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  const save = (apiKey: string | null, defaultModel: string) =>
    start(async () => {
      const r = await saveWorkspaceAiAction({ apiKey, defaultModel });
      if (!r.ok) {
        setError(r.error);
        return;
      }
      setStatus(r.data);
      setKey("");
      setError(null);
      setEditing(false);
      notify.ok(apiKey ? "Clave de IA guardada" : "Modelo actualizado");
    });

  return (
    <Section
      title="IA"
      description="La clave de API de Anthropic de tu empresa. Con ella trabajan los agentes «en el tablero» (por ejemplo, el Analista que arma informes de tus planillas). El consumo se cobra en tu cuenta de Anthropic."
    >
      <div className="flex flex-col gap-4">
        {status.configured && !editing ? (
          <div className="flex flex-wrap items-center gap-3 text-[13px]">
            <p className="min-w-0 flex-1 text-fg">
              Clave guardada <span className="font-mono text-fg-strong">sk-ant-{status.keyHint}</span>
            </p>
            <Button size="sm" onClick={() => setEditing(true)}>Cambiar clave</Button>
            <Button size="sm" tone="ghost" onClick={() => setConfirm(true)}>Quitar</Button>
          </div>
        ) : (
          <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); save(key, model); }}>
            <ol className="list-decimal space-y-1 pl-5 text-[13px] text-fg-muted">
              <li>Entra a <span className="font-medium text-fg">console.anthropic.com</span> con la cuenta de tu empresa.</li>
              <li>En <span className="font-medium text-fg">API Keys</span>, crea una clave (por ejemplo «Kuador Go») y cópiala.</li>
              <li>Pégala aquí. La comprobamos con Anthropic y la guardamos cifrada.</li>
            </ol>
            <Field label="Clave de API" error={error}>
              {(id) => <PasswordInput id={id} required autoComplete="off" value={key} onChange={(e) => setKey(e.target.value)} placeholder="sk-ant-…" />}
            </Field>
            <div className="flex justify-end gap-2">
              {status.configured && <Button tone="ghost" onClick={() => { setEditing(false); setError(null); }}>Cancelar</Button>}
              <Button type="submit" tone="primary" loading={pending}>Guardar clave</Button>
            </div>
          </form>
        )}
        {status.configured && (
          <div className="flex flex-wrap items-end gap-3 border-t border-line pt-4">
            <div className="w-80">
              <Field label="Modelo por defecto" hint="Cada agente puede usar otro en el equipo de su tablero.">
                {(id) => (
                  <SelectInput id={id} value={model} onChange={(v) => v && setModel(v)} options={status.models.map((m) => ({ value: m, label: MODEL_LABEL[m] ?? m }))} />
                )}
              </Field>
            </div>
            <Button loading={pending} disabled={model === status.defaultModel} onClick={() => save(null, model)}>Guardar modelo</Button>
          </div>
        )}
      </div>
      <ConfirmDialog
        open={confirm}
        onCancel={() => setConfirm(false)}
        onConfirm={() =>
          start(async () => {
            const r = await removeWorkspaceAiAction();
            setConfirm(false);
            if (!r.ok) {
              notify.error("No se pudo quitar", r.error);
              return;
            }
            setStatus(r.data);
            setEditing(true);
            notify.ok("Clave quitada");
          })
        }
        title="Quitar la clave de IA"
        message="Los agentes que trabajan en el tablero dejarán de trabajar hasta que pongas otra clave. Los que trabajan en la PC de una persona siguen igual."
        confirmLabel="Quitar clave"
        pending={pending}
      />
    </Section>
  );
}
