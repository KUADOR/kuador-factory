"use client";

import { useState, useTransition } from "react";

import { saveAiProviderAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextArea, TextInput } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { AiProvider } from "@/lib/types";

const PROTOCOL: Record<AiProvider["protocol"], string> = {
  ANTHROPIC: "API de Claude (con ejecución de código)",
  OPENAI_COMPATIBLE: "Compatible con OpenAI (herramientas del tablero)",
};

/**
 * Proveedores de IA que las empresas pueden elegir: dirección de la API y modelos que se ofrecen. Los nombres de
 * modelos cambian con el tiempo, por eso se escriben aquí (uno por línea, el primero es el recomendado).
 */
export function AiProvidersEditor({ providers: initial }: { providers: AiProvider[] }) {
  const [providers, setProviders] = useState(initial);
  return (
    <Section
      title="Proveedores de IA"
      description="Los que puede elegir cada empresa en Conexiones → IA. Un proveedor sin modelos o inactivo no se ofrece. Los modelos se copian de la documentación de cada proveedor; el primero es el recomendado."
    >
      <ul className="flex flex-col gap-3">
        {providers.map((p) => (
          <ProviderRow key={p.code} provider={p} onSaved={setProviders} />
        ))}
      </ul>
    </Section>
  );
}

function ProviderRow({ provider, onSaved }: { provider: AiProvider; onSaved: (all: AiProvider[]) => void }) {
  const [draft, setDraft] = useState({ ...provider, modelsText: provider.models.join("\n") });
  const [open, setOpen] = useState(false);
  const [pending, start] = useTransition();
  const offered = provider.active && provider.models.length > 0;

  return (
    <li className="rounded-md border border-line">
      <div className="flex flex-wrap items-center gap-3 px-3 py-2 text-[13px]">
        <span className="min-w-0 flex-1 font-medium text-fg-strong">{provider.name}</span>
        <span className="text-xs text-fg-muted">{offered ? `${provider.models.length} modelo${provider.models.length === 1 ? "" : "s"}` : provider.active ? "Sin modelos: no se ofrece" : "Inactivo"}</span>
        <Button size="sm" tone="ghost" onClick={() => setOpen(!open)}>{open ? "Cerrar" : "Editar"}</Button>
      </div>
      {open && (
        <form
          className="flex flex-col gap-3 border-t border-line px-3 py-3"
          onSubmit={(e) => {
            e.preventDefault();
            start(async () => {
              const r = await saveAiProviderAction(provider.code, {
                name: draft.name,
                protocol: draft.protocol,
                baseUrl: draft.baseUrl,
                models: draft.modelsText.split(/\s*\n\s*/).filter(Boolean),
                keyHint: draft.keyHint,
                active: draft.active,
                sortOrder: draft.sortOrder,
              });
              if (!r.ok) {
                notify.error("No se pudo guardar", r.error);
                return;
              }
              onSaved(r.data);
              setOpen(false);
              notify.ok(`${draft.name} guardado`);
            });
          }}
        >
          <p className="text-xs text-fg-muted">{PROTOCOL[provider.protocol]}</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Nombre">{(id) => <TextInput id={id} required value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />}</Field>
            <Field label="Dirección de la API">
              {(id) => <TextInput id={id} required className="font-mono" value={draft.baseUrl} onChange={(e) => setDraft({ ...draft, baseUrl: e.target.value })} />}
            </Field>
          </div>
          <Field label="Modelos" hint="Uno por línea, tal como los nombra el proveedor. El primero es el recomendado.">
            {(id) => <TextArea id={id} rows={4} className="font-mono" value={draft.modelsText} onChange={(e) => setDraft({ ...draft, modelsText: e.target.value })} />}
          </Field>
          <Field label="Cómo conseguir la clave" hint="Se muestra a la empresa al elegir este proveedor.">
            {(id) => <TextInput id={id} maxLength={120} value={draft.keyHint ?? ""} onChange={(e) => setDraft({ ...draft, keyHint: e.target.value || null })} />}
          </Field>
          <label className="flex items-center gap-2 text-[13px] text-fg">
            <input type="checkbox" checked={draft.active} onChange={(e) => setDraft({ ...draft, active: e.target.checked })} /> Activo
          </label>
          <div className="flex justify-end">
            <Button type="submit" tone="primary" loading={pending}>Guardar</Button>
          </div>
        </form>
      )}
    </li>
  );
}
