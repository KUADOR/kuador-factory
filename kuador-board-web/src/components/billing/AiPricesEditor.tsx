"use client";

import { Trash2 } from "lucide-react";
import { useState, useTransition } from "react";

import { deleteAiPriceAction, saveAiPriceAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextInput } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { AiModelPrice } from "@/lib/types";

/**
 * Precios de los modelos de IA (USD por millón de tokens), para estimar el consumo de cada espacio y aplicar su tope.
 * Se copian de la página de precios de cada proveedor; cambian con el tiempo, por eso no están fijos en el código.
 */
export function AiPricesEditor({ prices: initial, models }: { prices: AiModelPrice[]; models: string[] }) {
  const [prices, setPrices] = useState(initial);
  const [draft, setDraft] = useState({ model: models.find((m) => !initial.some((p) => p.model === m)) ?? "", input: "", output: "" });
  const [pending, start] = useTransition();
  const parse = (v: string) => Number(v.replace(",", "."));

  return (
    <Section
      title="Precios de modelos de IA"
      description="USD por millón de tokens, de la página de precios de cada proveedor. Con ellos cada espacio ve su costo estimado y su tope mensual funciona. Sin precio, se muestran solo los tokens."
    >
      <div className="flex flex-col gap-4">
        {prices.length > 0 && (
          <ul className="divide-y divide-line rounded-md border border-line text-[13px]">
            {prices.map((p) => (
              <li key={p.model} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2">
                <span className="min-w-0 flex-1 font-mono text-xs text-fg-strong">{p.model}</span>
                <span className="tabular text-fg-muted">entrada ${p.inputPerMTok}</span>
                <span className="tabular text-fg-muted">salida ${p.outputPerMTok}</span>
                <Button
                  size="sm"
                  tone="ghost"
                  icon={Trash2}
                  iconOnly
                  label={`Quitar precio de ${p.model}`}
                  disabled={pending}
                  onClick={() =>
                    start(async () => {
                      const r = await deleteAiPriceAction(p.model);
                      if (r.ok) setPrices(r.data);
                      else notify.error("No se pudo quitar", r.error);
                    })
                  }
                />
              </li>
            ))}
          </ul>
        )}
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            start(async () => {
              const r = await saveAiPriceAction(draft.model.trim(), parse(draft.input), parse(draft.output));
              if (!r.ok) {
                notify.error("No se pudo guardar", r.error);
                return;
              }
              setPrices(r.data);
              setDraft({ model: models.find((m) => !r.data.some((p) => p.model === m)) ?? "", input: "", output: "" });
              notify.ok("Precio guardado");
            });
          }}
        >
          <div className="w-56">
            <Field label="Modelo">{(id) => <TextInput id={id} required value={draft.model} onChange={(e) => setDraft({ ...draft, model: e.target.value })} className="font-mono" placeholder="claude-opus-5-5" />}</Field>
          </div>
          <div className="w-36">
            <Field label="Entrada (USD/M)">{(id) => <TextInput id={id} required inputMode="decimal" value={draft.input} onChange={(e) => setDraft({ ...draft, input: e.target.value })} />}</Field>
          </div>
          <div className="w-36">
            <Field label="Salida (USD/M)">{(id) => <TextInput id={id} required inputMode="decimal" value={draft.output} onChange={(e) => setDraft({ ...draft, output: e.target.value })} />}</Field>
          </div>
          <Button type="submit" loading={pending}>Guardar precio</Button>
        </form>
      </div>
    </Section>
  );
}
