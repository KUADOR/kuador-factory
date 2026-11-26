"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { setStagesAction, setTransitionsAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ChipPicker } from "@/components/ui/ChipPicker";
import { Field, SelectInput, Switch, TextArea, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { Project, StageDef } from "@/lib/types";

import { LeaveFields, ReviewFields } from "./StepFields";

export type Option = { value: string; label: string };

/**
 * Todo lo de un paso en un solo lugar: quién lo hace o quién aprueba, qué hace el agente, por dónde sigue según el
 * resultado, qué se comprueba y qué exige para salir. Lo poco frecuente queda plegado en «Más opciones».
 */
export function StepEditor({
  project,
  column,
  roleOptions,
  humanRoleOptions,
  documentOptions,
  onDone,
}: {
  project: Project;
  column: string;
  roleOptions: Option[];
  humanRoleOptions: Option[];
  /** Documentos por tarjeta del manual de trabajo del tablero: un paso puede exigirlos. */
  documentOptions: Option[];
  onDone: () => void;
}) {
  const router = useRouter();
  const name = project.columns.find((c) => c.key === column)?.name ?? column;
  const repository = project.capabilities.includes("REPOSITORY");
  const [stage, setStage] = useState<StageDef>(
    () => project.stages.find((s) => s.column === column) ?? { column, roles: [], gate: false, approvers: [], rejectTo: null },
  );
  const set = (patch: Partial<StageDef>) => setStage((s) => ({ ...s, ...patch }));
  // Las decisiones del paso: «si …» → otro paso (adelante o atrás).
  const [paths, setPaths] = useState<{ when: string; to: string }[]>(
    project.transitions.allowed.filter((t) => t.from === column && t.when).map((t) => ({ when: t.when ?? "", to: t.to })),
  );
  const [pending, start] = useTransition();
  const targets = project.columns.filter((c) => c.key !== column).map((c) => ({ value: c.key, label: c.name }));
  // Los datos propios de las tarjetas del tablero (de cualquier tipo), para poder exigirlos.
  const fieldOptions: Option[] = [];
  project.issueTypes.forEach((t) => (t.fields ?? []).forEach((f) => !fieldOptions.some((o) => o.value === f.key) && fieldOptions.push({ value: f.key, label: f.label })));

  // Pasar de trabajo a aprobación (o al revés) deja solo lo que aplica al nuevo tipo de paso.
  const setGate = (gate: boolean) =>
    set(
      gate
        ? { gate, roles: [], instructions: null, autoPlan: false, epicFirst: [] }
        : { gate, roles: [], approvers: [], rejectTo: null, review: null },
    );

  const save = () =>
    start(async () => {
      const edited: StageDef = { ...stage, instructions: !stage.gate && stage.roles.length > 0 ? stage.instructions?.trim() || null : null };
      const others = project.stages.filter((s) => s.column !== column);
      const meaningful = edited.gate || edited.roles.length > 0 || (edited.checklist ?? []).length > 0 || (edited.requires ?? []).length > 0;
      const r = await setStagesAction(project.key, meaningful ? [...others, edited] : others, project.maxRework);
      if (!r.ok) {
        notify.error("No se pudo guardar", r.error);
        return;
      }
      // Los caminos de este paso: se reemplazan las decisiones que salen de aquí; lo demás queda igual.
      const clean = edited.gate ? [] : paths.map((p) => ({ from: column, to: p.to, when: p.when.trim() })).filter((p) => p.to && p.when);
      const before = project.transitions.allowed;
      const changed = JSON.stringify(before.filter((t) => t.from === column && t.when).map((t) => [t.to, t.when])) !== JSON.stringify(clean.map((p) => [p.to, p.when]));
      if (changed) {
        const kept = before.filter((t) => !(t.from === column && (t.when || clean.some((p) => p.to === t.to))));
        const t = await setTransitionsAction(project.key, { restricted: project.transitions.restricted, allowed: [...kept, ...clean] });
        if (!t.ok) {
          notify.error("No se pudieron guardar los caminos", t.error);
          return;
        }
      }
      notify.ok("Paso guardado");
      onDone();
      router.refresh();
    });

  const moreOptions = stage.gate || stage.roles.length > 0;
  return (
    <form className="flex flex-col gap-5 p-5" onSubmit={(e) => { e.preventDefault(); save(); }}>
      <Switch checked={stage.gate} onChange={setGate} label="Aquí aprueba una persona antes de seguir" />

      {!stage.gate && (
        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium text-fg-muted">Quién lo hace</span>
          <ChipPicker value={stage.roles} options={roleOptions} onChange={(roles) => set({ roles })} placeholder="Agregar responsable…" ariaLabel={`Quién hace ${name}`} empty="cualquiera del equipo" />
        </div>
      )}
      {!stage.gate && stage.roles.length > 0 && (
        <Field label="Qué hace el agente en este paso" hint="Qué mirar, qué sistema usar y con qué criterio decide.">
          {(id) => <TextArea id={id} rows={6} maxLength={3000} value={stage.instructions ?? ""} onChange={(e) => set({ instructions: e.target.value })} />}
        </Field>
      )}

      {stage.gate && (
        <>
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-fg-muted">Quién aprueba</span>
            <ChipPicker value={stage.approvers} options={humanRoleOptions} onChange={(approvers) => set({ approvers })} placeholder="Agregar rol…" ariaLabel={`Quién aprueba ${name}`} empty="cualquier persona" />
          </div>
          <ReviewFields stage={stage} columnName={name} repository={repository} roleOptions={roleOptions} onChange={(review) => set({ review })} />
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-fg-muted">Si se rechaza, vuelve a</span>
            <SelectInput size="sm" ariaLabel={`A dónde vuelve una tarjeta rechazada en ${name}`} placeholder="El paso anterior" options={targets} value={stage.rejectTo} onChange={(rejectTo) => set({ rejectTo })} />
          </div>
        </>
      )}

      {!stage.gate && (
        <div className="flex flex-col gap-2">
          <span className="text-xs font-medium text-fg-muted">Caminos según el resultado</span>
          <span className="text-xs text-fg-faint">Si no se cumple ninguno, sigue al paso siguiente.</span>
          {paths.map((p, idx) => (
            <div key={idx} className="flex flex-wrap items-center gap-2">
              <TextInput
                aria-label="Condición"
                className="min-w-40 flex-1"
                maxLength={200}
                placeholder="si algo no coincide"
                value={p.when}
                onChange={(e) => setPaths((all) => all.map((x, j) => (j === idx ? { ...x, when: e.target.value } : x)))}
              />
              <span className="text-xs text-fg-muted">→</span>
              <div className="w-44">
                <SelectInput ariaLabel="A qué paso va" value={p.to || null} options={targets} placeholder="Elige el paso"
                  onChange={(to) => setPaths((all) => all.map((x, j) => (j === idx ? { ...x, to: to ?? "" } : x)))} />
              </div>
              <Button size="sm" tone="ghost" onClick={() => setPaths((all) => all.filter((_, j) => j !== idx))}>
                Quitar
              </Button>
            </div>
          ))}
          <div>
            <Button size="sm" onClick={() => setPaths((all) => [...all, { when: "", to: "" }])}>
              Agregar camino
            </Button>
          </div>
        </div>
      )}

      <LeaveFields stage={stage} columnName={name} repository={repository} fieldOptions={fieldOptions} documentOptions={documentOptions} onChange={set} />

      {moreOptions && (
        <details className="rounded-md border border-line">
          <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-fg-muted">Más opciones</summary>
          <div className="flex flex-col gap-3 border-t border-line p-3">
            {stage.gate ? (
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-fg-muted">Quién la prepara antes de aprobar</span>
                <ChipPicker value={stage.roles} options={roleOptions} onChange={(roles) => set({ roles })} placeholder="Agregar rol…" ariaLabel={`Quién prepara ${name}`} empty="nadie: la revisa directamente quien aprueba" />
              </div>
            ) : (
              <>
                <Switch checked={!!stage.autoPlan} onChange={(autoPlan) => set({ autoPlan })} label="El reparto de una épica arranca solo" />
                <p className="text-xs text-fg-faint">Las tareas empiezan sin que nadie apruebe el reparto.</p>
                <span className="mt-1 text-xs font-medium text-fg-muted">En épicas, antes trabaja</span>
                <ChipPicker
                  value={stage.epicFirst ?? []}
                  options={roleOptions.filter((o) => !stage.roles.includes(o.value))}
                  onChange={(epicFirst) => set({ epicFirst })}
                  placeholder="Agregar rol…"
                  ariaLabel={`Roles que trabajan antes las épicas en ${name}`}
                  empty="nadie: la toman directo quienes hacen el paso"
                />
              </>
            )}
          </div>
        </details>
      )}

      <div className="flex justify-end">
        <Button type="submit" tone="primary" loading={pending}>
          Guardar paso
        </Button>
      </div>
    </form>
  );
}
