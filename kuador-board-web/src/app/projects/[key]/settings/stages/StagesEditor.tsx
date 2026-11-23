"use client";

import { useRouter } from "next/navigation";
import { Lock } from "lucide-react";
import { clsx } from "clsx";
import { useState, useTransition } from "react";

import { setStagesAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ChipPicker } from "@/components/ui/ChipPicker";
import { SelectInput, Switch, TextArea, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { Project, Requirement, ReviewMode, StageDef } from "@/lib/types";

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
    hint: "La aprueba o la devuelve alguien con el rol aprobador.",
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

const CATEGORY_DOT = { TODO: "bg-fg-faint", IN_PROGRESS: "bg-info", DONE: "bg-ok" } as const;
type Option = { value: string; label: string };

/**
 * Etapas del flujo: por cada columna, quién la cubre y si es una puerta. Un issue que llega a una
 * etapa con responsables queda sin asignar y lo toma cualquiera con ese rol.
 */
export function StagesEditor({
  project,
  roleOptions,
  humanRoleOptions,
  documentOptions = [],
}: {
  project: Project;
  roleOptions: Option[];
  humanRoleOptions: Option[];
  /** Tipos de documento por tarjeta del manual del tablero (para exigirlos en una etapa). */
  documentOptions?: Option[];
}) {
  const router = useRouter();
  const repository = project.capabilities.includes("REPOSITORY");
  // Los datos propios de las tarjetas del tablero (de cualquier tipo), para poder exigirlos.
  const fieldOptions: Option[] = [];
  project.issueTypes.forEach((t) => (t.fields ?? []).forEach((f) => !fieldOptions.some((o) => o.value === f.key) && fieldOptions.push({ value: f.key, label: f.label })));
  const blank = (column: string): StageDef => ({
    column,
    roles: [],
    gate: false,
    approvers: [],
    rejectTo: null,
  });
  const [stages, setStages] = useState(() =>
    project.columns.map((c) => project.stages.find((s) => s.column === c.key) ?? blank(c.key)),
  );
  const [maxRework, setMaxRework] = useState(project.maxRework);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const set = (column: string, patch: Partial<StageDef>) =>
    setStages((all) => all.map((s) => (s.column === column ? { ...s, ...patch } : s)));
  const columnOptions = (except: string) =>
    project.columns.filter((c) => c.key !== except).map((c) => ({ value: c.key, label: c.name }));

  return (
    <div className="flex flex-col gap-4">
      <ol className="flex flex-col divide-y divide-line rounded-lg border border-line bg-surface">
        {project.columns.map((c, i) => {
          const s = stages.find((x) => x.column === c.key)!;
          return (
            <li
              key={c.key}
              className={clsx(
                "grid gap-3 px-5 py-4 lg:grid-cols-[13rem_minmax(0,1fr)_minmax(0,1fr)]",
                s.gate && "bg-sol/4",
              )}
            >
              <div className="flex items-start gap-2">
                <span className="tabular mt-0.5 w-4 font-mono text-xs text-fg-faint">{i + 1}</span>
                <span
                  aria-hidden
                  className={`mt-1.5 size-2 shrink-0 rounded-full ${CATEGORY_DOT[c.category]}`}
                />
                <div className="min-w-0">
                  <p className="flex items-center gap-1.5 text-[13px] font-medium text-fg-strong">
                    {c.name}
                    {s.gate && <Lock className="size-3.5 text-sol-text" aria-label="Puerta" />}
                  </p>
                  <div className="mt-2">
                    <Switch
                      checked={s.gate}
                      onChange={(gate) =>
                        set(c.key, {
                          gate,
                          approvers: gate ? s.approvers : [],
                          rejectTo: gate ? s.rejectTo : null,
                          review: gate ? s.review : null,
                          autoPlan: gate ? false : s.autoPlan,
                        })
                      }
                      label="Es una puerta"
                    />
                  </div>
                </div>
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-fg-muted">
                  {s.gate ? "Prepara (opcional)" : "La cubren"}
                </span>
                <ChipPicker
                  value={s.roles}
                  options={roleOptions}
                  onChange={(roles) => set(c.key, { roles })}
                  placeholder="Agregar rol…"
                  ariaLabel={`Roles responsables de ${c.name}`}
                  empty={
                    s.gate ? "nadie: la revisa directamente quien aprueba" : "cualquiera del equipo"
                  }
                />
              </div>
              {s.gate ? (
                <div className="flex flex-col gap-3">
                  <div className="flex flex-col gap-1.5">
                    <span className="text-xs font-medium text-fg-muted">
                      La aprueban (personas)
                    </span>
                    <ChipPicker
                      value={s.approvers}
                      options={humanRoleOptions}
                      onChange={(approvers) => set(c.key, { approvers })}
                      placeholder="Agregar rol…"
                      ariaLabel={`Roles que aprueban ${c.name}`}
                      empty="cualquier persona"
                    />
                  </div>
                  <ReviewFields
                    stage={s}
                    columnName={c.name}
                    repository={repository}
                    roleOptions={roleOptions}
                    onChange={(review) => set(c.key, { review })}
                  />
                  <div className="flex items-center gap-2">
                    <span className="shrink-0 text-xs font-medium text-fg-muted">
                      Si se rechaza, vuelve a
                    </span>
                    <div className="w-44">
                      <SelectInput
                        size="sm"
                        ariaLabel={`A dónde vuelve una tarjeta rechazada en ${c.name}`}
                        placeholder="La etapa anterior"
                        options={columnOptions(c.key)}
                        value={s.rejectTo}
                        onChange={(rejectTo) => set(c.key, { rejectTo })}
                      />
                    </div>
                  </div>
                </div>
              ) : (
                <div className="flex flex-col gap-2 self-center">
                  {s.roles.length > 0 && (
                    <>
                      <Switch
                        checked={!!s.autoPlan}
                        onChange={(autoPlan) => set(c.key, { autoPlan })}
                        label="El reparto de una épica arranca solo"
                      />
                      <p className="text-xs text-fg-faint">Las tareas empiezan sin que nadie apruebe el reparto.</p>
                    </>
                  )}
                </div>
              )}
              {!s.gate && s.roles.length > 0 && (
                <label className="flex flex-col gap-1 border-t border-line pt-3 text-xs font-medium text-fg-muted lg:col-span-3">
                  Qué hace el agente en este paso
                  <TextArea
                    rows={4}
                    maxLength={3000}
                    value={s.instructions ?? ""}
                    aria-label={`Qué hace el agente en ${c.name}`}
                    placeholder={"Lee la factura adjunta y busca su pedido en el ERP.\nSi todo coincide, regístrala en el ERP.\nSi algo no cuadra, no la registres: pregunta a una persona explicando el desfase."}
                    onChange={(e) => set(c.key, { instructions: e.target.value })}
                  />
                  <span className="font-normal text-fg-faint">En tus palabras: qué mirar, qué sistema usar y qué hacer si algo no cuadra.</span>
                </label>
              )}
              {(s.gate || s.roles.length > 0 || (s.checklist ?? []).length > 0 || (s.requires ?? []).length > 0) && (
                <LeaveFields
                  stage={s}
                  columnName={c.name}
                  fieldOptions={fieldOptions}
                  documentOptions={documentOptions}
                  onChange={(patch) => set(c.key, patch)}
                />
              )}
            </li>
          );
        })}
      </ol>

      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-line bg-surface px-5 py-4">
        <span className="text-[13px] font-medium text-fg-strong">Freno de bucles</span>
        <span className="text-[13px] text-fg-muted">Si una tarjeta vuelve atrás más de</span>
        <span className="w-16 shrink-0">
          <TextInput
            size="sm"
            type="number"
            min={1}
            max={20}
            value={maxRework}
            onChange={(e) => setMaxRework(Number(e.target.value) || 1)}
            aria-label="Vueltas atrás permitidas"
            className="tabular text-center"
          />
        </span>
        <span className="text-[13px] text-fg-muted">
          veces, se asigna a la persona responsable.
        </span>
      </div>

      {error && (
        <p role="alert" className="text-[13px] text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end">
        <Button
          tone="primary"
          size="sm"
          loading={pending}
          onClick={() =>
            start(async () => {
              const meaningful = stages
                .filter((s) => s.gate || s.roles.length > 0 || (s.checklist ?? []).length > 0 || (s.requires ?? []).length > 0 || !!s.instructions?.trim())
                .map((s) => ({ ...s, instructions: s.instructions?.trim() || null }));
              const r = await setStagesAction(project.key, meaningful, maxRework);
              if (r.ok) {
                setError(null);
                notify.ok("Etapas guardadas");
                router.refresh();
              } else setError(r.error);
            })
          }
        >
          Guardar etapas
        </Button>
      </div>
    </div>
  );
}

/** Quién cierra la puerta y, si es un agente, qué deja siempre a una persona. */
function ReviewFields({
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
      <span className="text-xs font-medium text-fg-muted">La cierra</span>
      <SelectInput<ReviewMode>
        size="sm"
        ariaLabel={`Quién cierra ${columnName}`}
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

/** Qué hay que verificar y qué debe tener la tarjeta para salir de la etapa. */
function LeaveFields({
  stage,
  columnName,
  fieldOptions,
  documentOptions,
  onChange,
}: {
  stage: StageDef;
  columnName: string;
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
    <div className="grid gap-3 border-t border-line pt-3 lg:col-span-3 lg:grid-cols-2">
      <label className="flex flex-col gap-1 text-xs font-medium text-fg-muted">
        {stage.gate ? "Lista de verificación para aprobar (una por línea)" : "Lista de verificación antes de entregar (una por línea)"}
        <TextArea
          rows={3}
          value={checklist}
          aria-label={`Lista de verificación de ${columnName}`}
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
