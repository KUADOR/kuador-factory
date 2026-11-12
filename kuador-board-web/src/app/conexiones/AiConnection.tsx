"use client";

import { useState, useTransition } from "react";

import { removeWorkspaceAiAction, saveWorkspaceAiAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { Field, PasswordInput, SelectInput } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { AiUsageReport, WorkspaceAiStatus } from "@/lib/types";

import { AiUsageCard } from "./AiUsageCard";

const MODEL_LABEL: Record<string, string> = {
  "claude-opus-5-5": "Claude Opus 5.5 (el más capaz)",
  "claude-sonnet-5-5": "Claude Sonnet 5.5 (equilibrado)",
  "claude-haiku-5-5": "Claude Haiku 5.5 (rápido y económico)",
};

/**
 * La IA de la empresa: el proveedor que usa (Claude, ChatGPT, DeepSeek…) y su clave de API. Con ella trabajan los
 * agentes «en el tablero». Se comprueba con el proveedor, se guarda cifrada y nunca se vuelve a mostrar. El consumo se
 * cobra en la cuenta de la empresa con ese proveedor.
 */
export function AiConnection({ status: initial, usage }: { status: WorkspaceAiStatus; usage: AiUsageReport | null }) {
  const [status, setStatus] = useState(initial);
  const [provider, setProvider] = useState(initial.provider ?? initial.providers[0]?.code ?? "");
  const [key, setKey] = useState("");
  const [model, setModel] = useState(initial.defaultModel);
  const [editing, setEditing] = useState(!initial.configured);
  const [confirm, setConfirm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const current = status.providers.find((p) => p.code === status.provider);
  const chosen = status.providers.find((p) => p.code === provider);

  const save = (apiKey: string | null, defaultModel: string) =>
    start(async () => {
      const r = await saveWorkspaceAiAction({ provider, apiKey, defaultModel });
      if (!r.ok) {
        setError(r.error);
        return;
      }
      setStatus(r.data);
      setModel(r.data.defaultModel);
      setKey("");
      setError(null);
      setEditing(false);
      notify.ok(apiKey ? "Clave de IA guardada" : "Modelo actualizado");
    });

  if (status.providers.length === 0) {
    return (
      <Section title="IA" description="La IA con la que trabajan los agentes «en el tablero».">
        <p className="text-[13px] text-fg-muted">Todavía no hay proveedores de IA habilitados. Escríbenos y te ayudamos a activarlos.</p>
      </Section>
    );
  }

  return (
    <Section
      title="IA"
      description="El proveedor de IA y la clave con la que trabajan tus agentes. El consumo se cobra en tu cuenta."
    >
      <div className="flex flex-col gap-4">
        {status.configured && !editing ? (
          <div className="flex flex-wrap items-center gap-3 text-[13px]">
            <p className="min-w-0 flex-1 text-fg">
              {current?.name ?? status.provider} · clave <span className="font-mono text-fg-strong">{status.keyHint}</span>
            </p>
            <Button size="sm" onClick={() => setEditing(true)}>Cambiar proveedor o clave</Button>
            <Button size="sm" tone="ghost" onClick={() => setConfirm(true)}>Quitar</Button>
          </div>
        ) : (
          <form
            className="flex flex-col gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              save(key, chosen?.models.includes(model) ? model : (chosen?.models[0] ?? model));
            }}
          >
            <div className="w-80">
              <Field label="Proveedor">
                {(id) => (
                  <SelectInput
                    id={id}
                    value={provider}
                    onChange={(v) => {
                      if (!v) return;
                      setProvider(v);
                      setModel(status.providers.find((p) => p.code === v)?.models[0] ?? "");
                      setError(null);
                    }}
                    options={status.providers.map((p) => ({ value: p.code, label: p.name }))}
                  />
                )}
              </Field>
            </div>
            {chosen && !chosen.codeExecution && (
              <p className="text-xs text-fg-muted">
                Con {chosen.name}, los agentes leen tus archivos (PDF, Word, Excel, CSV), hacen cuentas exactas sobre tus planillas y entregan
                documentos en PDF con las herramientas del tablero. No pueden ver imágenes ni PDF escaneados.
              </p>
            )}
            {chosen?.keyHint && <p className="text-[13px] text-fg-muted">{chosen.keyHint} Es una clave de API: se paga según el uso, aparte de cualquier suscripción de chat.</p>}
            <Field label="Clave de API" error={error}>
              {(id) => <PasswordInput id={id} required autoComplete="off" value={key} onChange={(e) => setKey(e.target.value)} placeholder="sk-…" />}
            </Field>
            <div className="flex justify-end gap-2">
              {status.configured && (
                <Button
                  tone="ghost"
                  onClick={() => {
                    setEditing(false);
                    setError(null);
                    setProvider(status.provider ?? provider);
                    setModel(status.defaultModel);
                  }}
                >
                  Cancelar
                </Button>
              )}
              <Button type="submit" tone="primary" loading={pending}>Guardar clave</Button>
            </div>
          </form>
        )}
        {status.configured && !editing && (
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
        {status.configured && usage && <AiUsageCard usage={usage} />}
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
