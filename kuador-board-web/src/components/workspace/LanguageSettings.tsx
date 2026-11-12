"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { updateLanguagesAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, SelectInput } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { Language, Workspace } from "@/lib/types";

const LANGUAGES: { value: Language; label: string }[] = [
  { value: "es", label: "Español" },
  { value: "en", label: "Inglés" },
  { value: "pt", label: "Portugués" },
];

/** Idioma del equipo y del código: lo reciben todos los agentes como regla, en su manual y en el kit del repo. */
export function LanguageSettings({ workspace }: { workspace: Workspace }) {
  const router = useRouter();
  const [team, setTeam] = useState<Language>(workspace.languages.team);
  const [code, setCode] = useState<Language>(workspace.languages.code);
  const [pending, start] = useTransition();
  const dirty = team !== workspace.languages.team || code !== workspace.languages.code;

  return (
    <Section
      title="Idiomas"
      description="En qué idioma escriben los agentes cada cosa."
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          start(async () => {
            const r = await updateLanguagesAction(team, code);
            if (r.ok) {
              notify.ok("Idiomas actualizados");
              router.refresh();
            } else notify.error("No se pudieron guardar los idiomas", r.error);
          });
        }}
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Idioma del equipo" hint="Specs, planes, comentarios del tablero y textos que ve el usuario final.">
            {(id) => <SelectInput id={id} options={LANGUAGES} value={team} onChange={(v) => v && setTeam(v)} />}
          </Field>
          <Field label="Idioma del código" hint="Nombres, comentarios, pruebas, commits, PRs y documentación de la API (OpenAPI).">
            {(id) => <SelectInput id={id} options={LANGUAGES} value={code} onChange={(v) => v && setCode(v)} />}
          </Field>
        </div>
        <div className="flex justify-end gap-2 border-t border-line pt-4">
          <Button tone="ghost" disabled={!dirty || pending} onClick={() => { setTeam(workspace.languages.team); setCode(workspace.languages.code); }}>
            Descartar
          </Button>
          <Button type="submit" tone="primary" loading={pending} disabled={!dirty}>
            Guardar idiomas
          </Button>
        </div>
      </form>
    </Section>
  );
}
