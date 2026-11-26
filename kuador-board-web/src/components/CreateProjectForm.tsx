"use client";

import { Sparkles } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { createProjectAction, setMethodologyAction } from "@/app/actions";
import { useAccess } from "@/components/shell/AccessContext";
import { Button } from "@/components/ui/Button";
import { ChipPicker } from "@/components/ui/ChipPicker";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { Field, TextInput } from "@/components/ui/Field";
import { isGeneratedTemplate } from "@/components/catalog/catalogTypes";
import { Badge } from "@/components/ui/Section";
import type { SubjectListing, WorkflowTemplate } from "@/lib/types";

/** «Atención de pacientes» → ATE: las primeras letras del nombre, sin tildes. */
function keyFrom(name: string) {
  const letters = name.normalize("NFD").replace(/[^A-Za-z0-9 ]/g, "").toUpperCase();
  const words = letters.split(/\s+/).filter((w) => /^[A-Z]/.test(w));
  if (words.length === 0) return "";
  const key = words.length >= 3 ? words.slice(0, 4).map((w) => w[0]).join("") : words[0].slice(0, 3);
  return key.length >= 2 ? key : (words[0] + "XX").slice(0, 3);
}

/** Las plantillas vienen del catálogo del espacio: cada una trae columnas y tipos de trabajo. */
export function CreateProjectForm({
  templates: all,
  recommended = [],
  subjects = [],
  onCancel,
}: {
  templates: WorkflowTemplate[];
  recommended?: string[];
  /** Los proyectos que ya existen en el espacio: el tablero puede trabajar en ellos en vez de nacer con uno propio. */
  subjects?: SubjectListing[];
  onCancel: () => void;
}) {
  // Las plantillas del paquete de la empresa van primero, en su orden.
  const rank = (key: string) => (recommended.includes(key) ? recommended.indexOf(key) : recommended.length);
  const templates = all.filter((t) => !isGeneratedTemplate(t)).sort((a, b) => rank(a.key) - rank(b.key));
  const router = useRouter();
  const { canAdminister } = useAccess();
  const [key, setKey] = useState("");
  // La clave se propone sola a partir del nombre, hasta que la persona la escribe.
  const [keyTouched, setKeyTouched] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [templateKey, setTemplateKey] = useState(recommended.find((k) => templates.some((t) => t.key === k)) ?? templates.find((t) => t.kind === "SOFTWARE")?.key ?? templates[0]?.key ?? "");
  const [origin, setOrigin] = useState<"new" | "existing">("new");
  const [chosen, setChosen] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const template = templates.find((t) => t.key === templateKey);

  return (
    <form
      className="flex flex-col gap-5 px-5 py-5"
      onSubmit={(e) => {
        e.preventDefault();
        startTransition(async () => {
          const result = await createProjectAction({ key, name, description: description.trim() || null, templateKey, subjects: chosen });
          if (!result.ok) return setError(result.error);
          if (template?.kind === "SOFTWARE" && origin === "existing" && chosen.length === 0) {
            // Un proyecto que ya existe: ODD (explorar antes de cambiar) y directo a vincular su repo.
            await setMethodologyAction(result.data.key, "ODD");
            router.push(`/projects/${result.data.key}/setup?step=repository&origen=existente`);
          } else router.push(`/projects/${result.data.key}/setup`);
        });
      }}
    >
      {canAdminister && (
        <Link href="/design" className="flex items-center gap-3 rounded-lg border border-sol/40 bg-sol/10 px-4 py-3 hover:bg-sol/15">
          <Sparkles className="size-5 shrink-0 text-sol-text" aria-hidden />
          <span className="min-w-0 flex-1">
            <span className="block text-[13px] font-medium text-fg-strong">Armarlo con IA para mi negocio</span>
            <span className="block text-xs text-fg-muted">Cuenta a qué se dedica tu empresa y te proponemos los pasos, los responsables y las aprobaciones.</span>
          </span>
        </Link>
      )}
      <div className="grid grid-cols-[minmax(0,1fr)_7rem] gap-3">
        <Field label="Nombre">
          {(id) => (
            <TextInput
              id={id}
              required
              autoFocus
              maxLength={120}
              value={name}
              onChange={(e) => {
                setName(e.target.value);
                if (!keyTouched) setKey(keyFrom(e.target.value));
              }}
              placeholder="Atención de pacientes"
            />
          )}
        </Field>
        <Field label="Clave" hint="Prefijo de las tarjetas.">
          {(id) => (
            <TextInput
              id={id}
              required
              maxLength={10}
              pattern="[A-Za-z][A-Za-z0-9]{1,9}"
              title="2 a 10 letras o números, empezando por letra"
              value={key}
              onChange={(e) => {
                setKeyTouched(true);
                setKey(e.target.value.toUpperCase());
              }}
              placeholder="ATE"
            />
          )}
        </Field>
      </div>
      <Field label="Descripción" hint="Opcional.">{(id) => <TextInput id={id} maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
      <div className="flex flex-col gap-2">
        <span className="flex items-center justify-between gap-3 text-xs font-medium text-fg-muted">
          O elige una plantilla
        </span>
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
            Pasos: {template.columns.map((c) => c.name).join(" → ")}.
          </p>
        )}
      </div>
      {subjects.length > 0 && (
        <div className="flex flex-col gap-2">
          <span className="text-xs font-medium text-fg-muted">¿En qué proyectos trabaja?</span>
          <ChipPicker
            value={chosen}
            options={subjects.map((s) => ({ value: s.key, label: s.name }))}
            onChange={setChosen}
            placeholder="Elegir proyecto"
            ariaLabel="Proyectos del tablero"
            empty="Uno nuevo, con el nombre del tablero."
          />
        </div>
      )}
      {template?.kind === "SOFTWARE" && chosen.length === 0 && (
        <div className="flex flex-col gap-2">
          <span className="text-xs font-medium text-fg-muted">El proyecto</span>
          <ChoiceCards
            name="origin"
            label="El proyecto"
            columns={2}
            value={origin}
            onChange={(v) => setOrigin(v as "new" | "existing")}
            choices={[
              { value: "new", label: "Es nuevo", hint: "El tablero crea los repos." },
              { value: "existing", label: "Ya existe", hint: "Vinculas su repo y el agente lo lee." },
            ]}
          />
        </div>
      )}
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
          Crear tablero
        </Button>
      </div>
    </form>
  );
}
