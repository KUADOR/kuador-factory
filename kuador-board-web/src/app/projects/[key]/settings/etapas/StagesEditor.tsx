"use client";

import { useRouter } from "next/navigation";
import { Lock } from "lucide-react";
import { clsx } from "clsx";
import { useState, useTransition } from "react";

import { setStagesAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ChipPicker } from "@/components/ui/ChipPicker";
import { SelectInput, Switch, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { Project, StageDef } from "@/lib/types";

const CATEGORY_DOT = { TODO: "bg-fg-faint", IN_PROGRESS: "bg-info", DONE: "bg-ok" } as const;
type Option = { value: string; label: string };

/**
 * Etapas del flujo: por cada columna, quién la cubre y si es una puerta. Un issue que llega a una
 * etapa con responsables queda sin asignar y lo toma cualquiera con ese rol.
 */
export function StagesEditor({ project, roleOptions, humanRoleOptions }: { project: Project; roleOptions: Option[]; humanRoleOptions: Option[] }) {
  const router = useRouter();
  const blank = (column: string): StageDef => ({ column, roles: [], gate: false, approvers: [], rejectTo: null });
  const [stages, setStages] = useState(() => project.columns.map((c) => project.stages.find((s) => s.column === c.key) ?? blank(c.key)));
  const [maxRework, setMaxRework] = useState(project.maxRework);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const set = (column: string, patch: Partial<StageDef>) => setStages((all) => all.map((s) => (s.column === column ? { ...s, ...patch } : s)));
  const columnOptions = (except: string) => project.columns.filter((c) => c.key !== except).map((c) => ({ value: c.key, label: c.name }));

  return (
    <div className="flex flex-col gap-4">
      <ol className="flex flex-col divide-y divide-line rounded-lg border border-line bg-surface">
        {project.columns.map((c, i) => {
          const s = stages.find((x) => x.column === c.key)!;
          return (
            <li key={c.key} className={clsx("grid gap-3 px-5 py-4 lg:grid-cols-[13rem_minmax(0,1fr)_minmax(0,1fr)]", s.gate && "bg-sol/4")}>
              <div className="flex items-start gap-2">
                <span className="tabular mt-0.5 w-4 font-mono text-xs text-fg-faint">{i + 1}</span>
                <span aria-hidden className={`mt-1.5 size-2 shrink-0 rounded-full ${CATEGORY_DOT[c.category]}`} />
                <div className="min-w-0">
                  <p className="flex items-center gap-1.5 text-[13px] font-medium text-fg-strong">
                    {c.name}
                    {s.gate && <Lock className="size-3.5 text-sol-text" aria-label="Puerta" />}
                  </p>
                  <div className="mt-2">
                    <Switch checked={s.gate} onChange={(gate) => set(c.key, { gate, approvers: gate ? s.approvers : [], rejectTo: gate ? s.rejectTo : null })} label="Es una puerta" />
                  </div>
                </div>
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-fg-muted">{s.gate ? "Prepara (opcional)" : "La cubren"}</span>
                <ChipPicker
                  value={s.roles}
                  options={roleOptions}
                  onChange={(roles) => set(c.key, { roles })}
                  placeholder="Agregar rol…"
                  ariaLabel={`Roles responsables de ${c.name}`}
                  empty={s.gate ? "nadie: la revisa directamente quien aprueba" : "cualquiera del equipo"}
                />
              </div>
              {s.gate ? (
                <div className="flex flex-col gap-3">
                  <div className="flex flex-col gap-1.5">
                    <span className="text-xs font-medium text-fg-muted">La aprueba (solo personas)</span>
                    <ChipPicker
                      value={s.approvers}
                      options={humanRoleOptions}
                      onChange={(approvers) => set(c.key, { approvers })}
                      placeholder="Agregar rol…"
                      ariaLabel={`Roles que aprueban ${c.name}`}
                      empty="cualquier persona"
                    />
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="shrink-0 text-xs font-medium text-fg-muted">Si se rechaza, vuelve a</span>
                    <div className="w-44">
                      <SelectInput
                        size="sm"
                        ariaLabel={`A dónde vuelve un issue rechazado en ${c.name}`}
                        placeholder="La etapa anterior"
                        options={columnOptions(c.key)}
                        value={s.rejectTo}
                        onChange={(rejectTo) => set(c.key, { rejectTo })}
                      />
                    </div>
                  </div>
                </div>
              ) : (
                <p className="self-center text-xs text-fg-faint">
                  {s.roles.length > 0 ? "Al llegar aquí el issue queda sin asignar hasta que alguien con ese rol lo tome." : "Etapa libre: se asigna a mano como siempre."}
                </p>
              )}
            </li>
          );
        })}
      </ol>

      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-line bg-surface px-5 py-4">
        <span className="text-[13px] font-medium text-fg-strong">Freno de bucles</span>
        <span className="text-[13px] text-fg-muted">Si un issue vuelve atrás más de</span>
        <TextInput
          size="sm"
          type="number"
          min={1}
          max={20}
          value={maxRework}
          onChange={(e) => setMaxRework(Number(e.target.value) || 1)}
          aria-label="Vueltas atrás permitidas"
          className="tabular w-16 text-center"
        />
        <span className="text-[13px] text-fg-muted">veces, se asigna a la persona responsable.</span>
      </div>

      {error && <p role="alert" className="text-[13px] text-danger">{error}</p>}
      <div className="flex justify-end">
        <Button
          tone="primary"
          size="sm"
          loading={pending}
          onClick={() =>
            start(async () => {
              const meaningful = stages.filter((s) => s.gate || s.roles.length > 0);
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
