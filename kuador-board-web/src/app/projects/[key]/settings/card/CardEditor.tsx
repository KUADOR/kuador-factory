"use client";

import { useState, useTransition } from "react";

import { setCardAction } from "@/app/actions";
import { FieldsEditor, cleanFields } from "@/components/board/FieldsEditor";
import { Button } from "@/components/ui/Button";
import { Field, SelectInput, TextInput } from "@/components/ui/Field";
import { TypeIcon } from "@/components/ui/IssueIcons";
import { notify } from "@/components/ui/Toaster";
import { ESTIMATE_LABEL } from "@/lib/labels";
import { ESTIMATE_UNITS, type EstimateUnit, type IssueTypeDef, type Project } from "@/lib/types";

/** La forma de la tarjeta en este tablero: cómo se llama cada tipo, qué datos lleva y en qué se mide su tamaño. */
export function CardEditor({ project }: { project: Project }) {
  const [types, setTypes] = useState<IssueTypeDef[]>(project.issueTypes.map((t) => ({ ...t, fields: t.fields ?? [] })));
  const [estimate, setEstimate] = useState<EstimateUnit>(project.estimate ?? "POINTS");
  const [pending, start] = useTransition();
  const patch = (i: number, change: Partial<IssueTypeDef>) => setTypes((all) => all.map((t, j) => (j === i ? { ...t, ...change } : t)));
  return (
    <form
      className="flex flex-col gap-6"
      onSubmit={(e) => {
        e.preventDefault();
        start(async () => {
          const r = await setCardAction(project.key, types.map((t) => ({ ...t, label: t.label.trim(), fields: cleanFields(t.fields ?? []) })), estimate);
          if (r.ok) {
            setTypes(r.data.issueTypes.map((t) => ({ ...t, fields: t.fields ?? [] })));
            notify.ok("Tarjeta guardada");
          } else notify.error("No se pudo guardar", r.error);
        });
      }}
    >
      {types.map((t, i) => (
        <div key={t.type} className="flex flex-col gap-3 border-b border-line pb-5">
          <div className="grid grid-cols-[1.25rem_minmax(0,16rem)] items-center gap-2">
            <TypeIcon type={t.type} projectType={project} />
            <TextInput size="sm" required maxLength={40} value={t.label} aria-label={`Nombre del tipo ${t.type}`} onChange={(e) => patch(i, { label: e.target.value })} />
          </div>
          <FieldsEditor typeLabel={t.label} value={t.fields ?? []} onChange={(fields) => patch(i, { fields })} />
        </div>
      ))}
      <div className="max-w-64">
        <Field label="Tamaño de la tarjeta">
          {(id) => (
            <SelectInput id={id} size="sm" options={ESTIMATE_UNITS.map((u) => ({ value: u, label: ESTIMATE_LABEL[u] }))} value={estimate} onChange={(v) => v && setEstimate(v)} />
          )}
        </Field>
      </div>
      <div className="flex justify-end">
        <Button type="submit" tone="primary" loading={pending}>
          Guardar
        </Button>
      </div>
    </form>
  );
}
