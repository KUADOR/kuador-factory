"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { createProjectAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { Field, TextInput } from "@/components/ui/Field";
import { Badge } from "@/components/ui/Section";
import type { WorkflowTemplate } from "@/lib/types";

/** Las plantillas vienen del catálogo del espacio: cada una trae columnas y tipos de trabajo. */
export function CreateProjectForm({ templates: all, recommended = [], onCancel }: { templates: WorkflowTemplate[]; recommended?: string[]; onCancel: () => void }) {
  // Las plantillas del paquete de la empresa van primero, en su orden.
  const rank = (key: string) => (recommended.includes(key) ? recommended.indexOf(key) : recommended.length);
  const templates = [...all].sort((a, b) => rank(a.key) - rank(b.key));
  const router = useRouter();
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [templateKey, setTemplateKey] = useState(recommended.find((k) => templates.some((t) => t.key === k)) ?? templates.find((t) => t.kind === "SOFTWARE")?.key ?? templates[0]?.key ?? "");
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const template = templates.find((t) => t.key === templateKey);

  return (
    <form
      className="flex flex-col gap-5 px-5 py-5"
      onSubmit={(e) => {
        e.preventDefault();
        startTransition(async () => {
          const result = await createProjectAction({ key, name, description: description.trim() || null, templateKey });
          if (result.ok) router.push(`/projects/${result.data.key}/setup`);
          else setError(result.error);
        });
      }}
    >
      <div className="grid grid-cols-[7rem_minmax(0,1fr)] gap-3">
        <Field label="Clave" hint="Prefijo de los issues.">
          {(id) => (
            <TextInput
              id={id}
              required
              maxLength={10}
              pattern="[A-Za-z][A-Za-z0-9]{1,9}"
              title="2 a 10 letras o números, empezando por letra"
              value={key}
              onChange={(e) => setKey(e.target.value.toUpperCase())}
              placeholder="MED"
              className="font-mono uppercase"
              autoFocus
            />
          )}
        </Field>
        <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder="Medical para Clínica Andes" />}</Field>
      </div>
      <Field label="Descripción" hint="Opcional.">{(id) => <TextInput id={id} maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
      <div className="flex flex-col gap-2">
        <span className="text-xs font-medium text-fg-muted">Plantilla de flujo</span>
        <ChoiceCards
          name="template"
          label="Plantilla de flujo"
          columns={2}
          value={templateKey}
          onChange={setTemplateKey}
          choices={templates.map((t) => ({
            value: t.key,
            label: t.name,
            hint: t.description ?? undefined,
            badge: recommended.includes(t.key) ? <Badge tone="sol">Recomendada</Badge> : t.kind === "SOFTWARE" ? <Badge tone="info">Software</Badge> : undefined,
          }))}
        />
        {template && (
          <p className="text-xs text-fg-faint">
            Columnas: {template.columns.map((c) => c.name).join(" → ")}. Se pueden cambiar después.
          </p>
        )}
      </div>
      {error && (
        <p role="alert" className="text-[13px] text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button tone="ghost" onClick={onCancel}>
          Cancelar
        </Button>
        <Button type="submit" tone="primary" loading={pending}>
          Crear proyecto
        </Button>
      </div>
    </form>
  );
}
