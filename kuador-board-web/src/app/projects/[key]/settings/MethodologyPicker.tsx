"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { setMethodologyAction, setSubjectMethodologyAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { Badge } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import { documentsOf } from "@/lib/labels";
import type { Methodology, Project } from "@/lib/types";

/** specs/{feature}/spec.md → specs/NNN-nombre/spec.md */
const readablePath = (path: string) => path.replace("{feature}", "NNN-nombre").replace("{task}", "clave-nombre");
const NONE = "__none";

/**
 * Las metodologías salen del catálogo del espacio (de fábrica o creadas por el equipo). El manual es del proyecto: se
 * elige desde el proyecto (`subject`) o, en el asistente de un tablero, para el proyecto que mira (`project`).
 */
export function MethodologyPicker({
  project,
  subject,
  methodologies,
}: {
  project?: Project;
  subject?: { key: string; methodology: string | null };
  methodologies: Methodology[];
}) {
  const router = useRouter();
  const current = (subject ? subject.methodology : project?.methodology) ?? null;
  const [selected, setSelected] = useState<string>(current ?? NONE);
  const [pending, startTransition] = useTransition();
  const guide = methodologies.find((m) => m.key === selected);
  const dirty = selected !== (current ?? NONE);
  const docsHref = subject ? `/org/projects/${subject.key}/docs` : `/projects/${project?.key}/docs`;

  return (
    <div className="flex flex-col gap-4">
      <ChoiceCards
        name="methodology"
        label="Metodología"
        columns={4}
        value={selected}
        onChange={setSelected}
        choices={[
          ...methodologies.map((m) => ({ value: m.key, label: m.key === "FREE" ? "Libre" : m.key, hint: m.name, badge: m.builtIn ? undefined : <Badge>Propia</Badge> })),
          { value: NONE, label: "Ninguna", hint: "Sin documentos" },
        ]}
      />

      {guide && (
        <div className="flex flex-col gap-4">
          <p className="max-w-3xl text-[13px] leading-relaxed text-fg">{guide.summary}</p>
          <div className="grid gap-4 md:grid-cols-2">
            {[
              { title: "Documentos del proyecto", rules: documentsOf(guide, "PROJECT") },
              { title: "Documentos por tarjeta", rules: documentsOf(guide, "ISSUE") },
            ].map(({ title, rules }) => (
              <div key={title}>
                <h3 className="mb-2 text-xs font-medium text-fg-muted">{title}</h3>
                {rules.length === 0 ? (
                  <p className="text-xs text-fg-faint">Ninguno.</p>
                ) : (
                  <ul className="flex flex-col gap-2">
                    {rules.map((r) => (
                      <li key={r.kind} className="text-xs leading-relaxed">
                        <span className="flex items-center gap-1.5 font-medium text-fg-strong">
                          {r.label}
                          {r.required && <Badge tone="warn">Obligatorio</Badge>}
                        </span>
                        <span className="block text-fg-muted">{r.purpose}</span>
                        <code className="font-mono text-[11px] text-fg-faint">{readablePath(r.pathPattern)}</code>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </div>
          {guide.agentRules.length > 0 && (
            <div>
              <h3 className="mb-2 text-xs font-medium text-fg-muted">Cómo trabajan los agentes</h3>
              <ol className="flex list-decimal flex-col gap-1 pl-5 text-xs leading-relaxed text-fg">
                {guide.agentRules.map((rule) => (
                  <li key={rule}>{rule}</li>
                ))}
              </ol>
            </div>
          )}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3">
        {current && !dirty && (
          <Link href={docsHref} className="k-link text-[13px]">
            Ir a los documentos
          </Link>
        )}
        <Button
          tone="primary"
          size="sm"
          className="ml-auto"
          disabled={!dirty}
          loading={pending}
          onClick={() =>
            startTransition(async () => {
              const chosen = selected === NONE ? null : selected;
              const result = subject ? await setSubjectMethodologyAction(subject.key, chosen) : await setMethodologyAction(project?.key ?? "", chosen);
              if (result.ok) {
                notify.ok("Metodología guardada", "Los documentos que ya existen se conservan.");
                router.refresh();
              } else notify.error("No se pudo guardar", result.error);
            })
          }
        >
          Guardar metodología
        </Button>
      </div>
    </div>
  );
}
