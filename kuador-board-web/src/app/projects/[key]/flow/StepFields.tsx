"use client";

import { useState } from "react";

import { ChipPicker } from "@/components/ui/ChipPicker";
import { SelectInput, Switch, TextArea, TextInput } from "@/components/ui/Field";
import type { Requirement, ReviewMode, StageDef } from "@/lib/types";

/** Lo que conviene que decida siempre una persona, para empezar (se puede editar). */
const DEFAULT_SENSITIVE = [
  "**/db/migration/**",
  "**/security/**",
  "**/*.env*",
  ".github/workflows/**",
];

const REVIEW_CHOICES: { value: ReviewMode; label: string; hint: string }[] = [
  {
    value: "PERSON",
    label: "Una persona",
    hint: "Aprueba o devuelve alguien con el rol que aprueba.",
  },
  {
    value: "AGENT_ESCALATE",
    label: "Agente revisor, con escalamiento (recomendado)",
    hint: "El agente aprueba lo claro, devuelve lo que falla y deja lo dudoso a una persona.",
  },
  {
    value: "AGENT",
    label: "Agente revisor",
    hint: "El agente aprueba o devuelve; solo pasa a una persona si no puede decidir.",
  },
];

type Option = { value: string; label: string };

/** Cómo se revisa una aprobación: una persona o un agente revisor y, si es un agente, qué deja siempre a una persona. */
export function ReviewFields({
  stage,
  columnName,
  repository,
  roleOptions,
  onChange,
}: {
  stage: StageDef;
  columnName: string;
  /** El tablero trabaja con un repositorio de código: la revisión mira el PR y su CI. */
  repository: boolean;
  roleOptions: Option[];
  onChange: (review: StageDef["review"]) => void;
}) {
  const mode: ReviewMode = stage.review?.mode ?? "PERSON";
  const choice = REVIEW_CHOICES.find((c) => c.value === mode)!;
  const [sensitive, setSensitive] = useState(
    (stage.review?.sensitive ?? DEFAULT_SENSITIVE).join("\n"),
  );
  const review = stage.review ?? {
    mode: "AGENT_ESCALATE" as ReviewMode,
    // Sin repositorio revisa el Revisor (de cualquier trabajo); con repositorio, el Revisor de PR (por defecto).
    reviewerRole: repository ? null : "REVIEWER",
    sensitive: repository ? DEFAULT_SENSITIVE : [],
    maxChangedLines: repository ? 5000 : null,
  };
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-xs font-medium text-fg-muted">Cómo se revisa</span>
      <SelectInput<ReviewMode>
        size="sm"
        ariaLabel={`Cómo se revisa ${columnName}`}
        value={mode}
        options={REVIEW_CHOICES.map((c) => ({ value: c.value, label: c.label }))}
        onChange={(v) => v && onChange(v === "PERSON" ? null : { ...review, mode: v })}
      />
      <p className="text-xs text-fg-faint">{choice.hint}</p>
      {mode !== "PERSON" && (
        <div className="mt-1 flex flex-col gap-2">
          <div className="flex items-center gap-2">
            <span className="shrink-0 text-xs text-fg-muted">Revisa el agente</span>
            <div className="w-52">
              <SelectInput
                size="sm"
                ariaLabel={`Rol del agente que revisa ${columnName}`}
                placeholder={repository ? "Revisor de PR" : "Elige un rol"}
                options={roleOptions}
                value={review.reviewerRole}
                onChange={(reviewerRole) => onChange({ ...review, mode, reviewerRole })}
              />
            </div>
          </div>
          {repository && (
            <>
              <p className="text-xs text-fg-muted">Revisa cuando termina el CI; si queda en rojo, la devuelve sin revisar.</p>
              <Switch
                checked={!!review.merge}
                onChange={(merge) => onChange({ ...review, mode, merge })}
                label="Al aprobar, fusionar el PR"
              />
              <p className="text-xs text-fg-faint">
                {review.merge
                  ? "El tablero fusiona lo que el agente revisó. Con conflictos la devuelve; lo dudoso te lo deja a ti."
                  : "El agente aprueba o devuelve; el merge lo hace una persona."}
              </p>
            </>
          )}
          {mode === "AGENT_ESCALATE" && repository && (
            <>
              <label className="flex flex-col gap-1 text-xs text-fg-muted">
                Rutas que siempre decide una persona (una por línea)
                <TextArea
                  rows={3}
                  value={sensitive}
                  className="font-mono text-xs"
                  onChange={(e) => {
                    setSensitive(e.target.value);
                    onChange({
                      ...review,
                      sensitive: e.target.value
                        .split("\n")
                        .map((x) => x.trim())
                        .filter(Boolean),
                    });
                  }}
                />
              </label>
              <label className="flex items-center gap-2 text-xs text-fg-muted">
                Más de
                <span className="w-20 shrink-0">
                  <TextInput
                    size="sm"
                    type="number"
                    min={0}
                    max={100000}
                    value={review.maxChangedLines ?? 0}
                    onChange={(e) =>
                      onChange({
                        ...review,
                        maxChangedLines: Number(e.target.value) > 0 ? Number(e.target.value) : null,
                      })
                    }
                    aria-label="Líneas cambiadas que pasan a una persona"
                    className="tabular text-center"
                  />
                </span>
                líneas cambiadas (sin lockfiles ni generados) pasa a una persona (0 = sin límite)
              </label>
            </>
          )}
        </div>
      )}
    </div>
  );
}

/** Qué se comprueba y qué debe tener la tarjeta para salir del paso. */
export function LeaveFields({
  stage,
  columnName,
  repository,
  fieldOptions,
  documentOptions,
  onChange,
}: {
  stage: StageDef;
  columnName: string;
  repository: boolean;
  fieldOptions: Option[];
  documentOptions: Option[];
  onChange: (patch: Partial<StageDef>) => void;
}) {
  const [checklist, setChecklist] = useState((stage.checklist ?? []).join("\n"));
  const requires = stage.requires ?? [];
  const refs = (kind: Requirement["kind"]) => requires.filter((r) => r.kind === kind && r.ref).map((r) => r.ref as string);
  const replace = (kind: Requirement["kind"], values: (string | null)[]) =>
    onChange({ requires: [...requires.filter((r) => r.kind !== kind), ...values.map((ref) => ({ kind, ref }))] });
  return (
    <div className="flex flex-col gap-4">
      <label className="flex flex-col gap-1 text-xs font-medium text-fg-muted">
        {stage.gate ? "Qué comprueba quien aprueba (uno por línea)" : "Qué se comprueba antes de entregar (uno por línea)"}
        <TextArea
          rows={3}
          value={checklist}
          aria-label={`Qué se comprueba en ${columnName}`}
          placeholder={"La propuesta tiene precio y plazo\nLos datos del cliente están completos"}
          onChange={(e) => {
            setChecklist(e.target.value);
            onChange({ checklist: e.target.value.split("\n").map((x) => x.trim()).filter(Boolean) });
          }}
        />
      </label>
      <div className="flex flex-col gap-2">
        <span className="text-xs font-medium text-fg-muted">No sale de aquí sin</span>
        <Switch
          checked={requires.some((r) => r.kind === "ATTACHMENT")}
          onChange={(on) => replace("ATTACHMENT", on ? [null] : [])}
          label="Un archivo adjunto"
        />
        {repository && !stage.gate && (
          <Switch
            checked={requires.some((r) => r.kind === "DIAGRAM")}
            onChange={(on) => replace("DIAGRAM", on ? [null] : [])}
            label="En épicas, el diagrama de la solución"
          />
        )}
        {fieldOptions.length > 0 && (
          <ChipPicker
            value={refs("FIELD")}
            options={fieldOptions}
            onChange={(keys) => replace("FIELD", keys)}
            placeholder="Agregar dato…"
            ariaLabel={`Datos que ${columnName} exige`}
            empty="ningún dato de la tarjeta"
          />
        )}
        {documentOptions.length > 0 && (
          <ChipPicker
            value={refs("DOCUMENT")}
            options={documentOptions}
            onChange={(kinds) => replace("DOCUMENT", kinds)}
            placeholder="Agregar documento…"
            ariaLabel={`Documentos que ${columnName} exige`}
            empty="ningún documento"
          />
        )}
      </div>
    </div>
  );
}
