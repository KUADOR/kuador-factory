"use client";

import { Copy, Laptop, Trash } from "lucide-react";
import Link from "next/link";
import { useTransition } from "react";
import { clsx } from "clsx";

import { deleteRunnerMachineAction, updateRunnerMachineAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { SelectInput, Switch } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { RunnerMachine } from "@/lib/types";

const OS: Record<string, string> = { macos: "Mac", linux: "Linux", windows: "Windows" };

const ago = (iso: string) => {
  const min = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (min < 1) return "ahora";
  if (min < 60) return `hace ${min} min`;
  const h = Math.round(min / 60);
  return h < 24 ? `hace ${h} h` : `hace ${Math.round(h / 24)} d`;
};

/**
 * El runner: deja a los agentes trabajando solos en la máquina de cada persona, con su suscripción. Un comando para
 * instalarlo (pregunta qué roles atiende la máquina) y la lista de máquinas con lo que hace cada una.
 */
export function RunnerSection({ machines, server, roleName, meId }: { machines: RunnerMachine[]; server: string; roleName: Record<string, string>; meId: string }) {
  const command = `npx @kuador-npm/runner install --server ${server}`;
  return (
    <Section
      title="Que trabajen solos: el runner"
      description="Un programa pequeño que se queda escuchando al tablero en tu Mac, Linux o Windows. Cuando hay trabajo para los roles que elijas, lanza tu Claude Code con tu cuenta (sin claves de API) y la tarjeta avanza aunque no estés frente a la PC. Las puertas siguen siendo de una persona."
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-2">
          <p className="text-[13px] text-fg-muted">
            En la terminal de la máquina (necesita Node 20 o más y Claude Code con tu sesión iniciada). Te muestra un código para aprobar aquí y te pregunta
            qué roles atiende:
          </p>
          <div className="relative">
            <pre className="overflow-x-auto rounded-md border border-line bg-sunken px-3 py-2.5 pr-10 font-mono text-[12px] text-fg-strong">{command}</pre>
            <span className="absolute top-1.5 right-1.5">
              <Button
                size="sm"
                tone="ghost"
                icon={Copy}
                iconOnly
                label="Copiar el comando"
                onClick={async () => {
                  await navigator.clipboard.writeText(command);
                  notify.ok("Copiado");
                }}
              />
            </span>
          </div>
          <p className="text-xs text-fg-faint">
            ¿Ya te mostró un código? Apruébalo en <Link href="/local-agents/pair" className="k-link">Aprobar una máquina</Link>.
          </p>
        </div>

        {machines.length === 0 ? (
          <p className="rounded-md border border-dashed border-line-strong px-4 py-3 text-[13px] text-fg-muted">Todavía no hay máquinas con runner.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
            {machines.map((m) => (
              <MachineRow key={m.id} machine={m} roleName={roleName} mine={m.memberId === meId} />
            ))}
          </ul>
        )}
      </div>
    </Section>
  );
}

function MachineRow({ machine: m, roleName, mine }: { machine: RunnerMachine; roleName: Record<string, string>; mine: boolean }) {
  const [pending, start] = useTransition();
  const state = !m.online ? "Desconectada" : m.paused ? "En pausa" : m.working.length ? `Trabajando ${m.working.join(", ")}` : "Esperando trabajo";
  return (
    <li className="flex flex-col gap-2 px-4 py-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <Laptop className="size-4 shrink-0 text-fg-muted" aria-hidden />
        <span className="text-[14px] font-medium text-fg-strong">{m.name}</span>
        <span className="text-xs text-fg-faint">
          {OS[m.os] ?? m.os} · {mine ? "tuya" : m.person} · hasta {m.capacity} a la vez · hoy {Math.round(m.minutesToday)} min
          {m.dailyMinutes != null && ` de ${m.dailyMinutes}`}
        </span>
        <span className={clsx("ml-auto flex items-center gap-1.5 text-xs", m.online && !m.paused ? "text-fg" : "text-fg-muted")}>
          <span aria-hidden className={clsx("size-2 rounded-full", !m.online ? "bg-fg-faint" : m.paused ? "bg-warn" : "bg-ok")} />
          {state}
          {!m.online && <span className="text-fg-faint">· {ago(m.lastSeen)}</span>}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-1.5">
        {m.roles.map((r) => (
          <span key={r} className="rounded-full border border-line px-2 py-0.5 text-[11px] text-fg-muted">
            {roleName[r] ?? r}
          </span>
        ))}
        <span className="ml-auto flex items-center gap-2">
          {/* Con la suscripción de la persona el freno es de tiempo al día. */}
          <span className="w-36">
            <SelectInput<number>
              size="sm"
              ariaLabel={`Tope por día de ${m.name}`}
              value={m.dailyMinutes ?? 0}
              disabled={pending}
              onChange={(v) =>
                start(async () => {
                  const minutes = v ?? 0;
                  const r = await updateRunnerMachineAction(m.id, minutes === 0 ? { noDailyLimit: true } : { dailyMinutes: minutes });
                  if (r.ok) notify.ok(minutes === 0 ? `«${m.name}» sin tope por día` : `«${m.name}» trabaja hasta ${minutes / 60} h al día`);
                  else notify.error("No se pudo cambiar", r.error);
                })
              }
              options={[
                { value: 0, label: "Sin tope al día" },
                { value: 60, label: "Hasta 1 h al día" },
                { value: 120, label: "Hasta 2 h al día" },
                { value: 240, label: "Hasta 4 h al día" },
                { value: 480, label: "Hasta 8 h al día" },
              ]}
            />
          </span>
          <Switch
            checked={!m.paused}
            disabled={pending}
            label={m.paused ? "Pausada" : "Toma tarjetas"}
            onChange={(on) =>
              start(async () => {
                const r = await updateRunnerMachineAction(m.id, { paused: !on });
                if (r.ok) notify.ok(on ? `«${m.name}» vuelve a tomar tarjetas` : `«${m.name}» en pausa: termina lo que tiene y no toma más`);
                else notify.error("No se pudo cambiar", r.error);
              })
            }
          />
          <Button
            size="sm"
            tone="ghost"
            icon={Trash}
            iconOnly
            label={`Quitar ${m.name}`}
            disabled={pending}
            onClick={() =>
              start(async () => {
                if (!window.confirm(`¿Quitar «${m.name}»? Deja de recibir trabajo; para volver a usarla hay que instalar de nuevo.`)) return;
                const r = await deleteRunnerMachineAction(m.id);
                if (r.ok) notify.ok(`«${m.name}» quitada`);
                else notify.error("No se pudo quitar", r.error);
              })
            }
          />
        </span>
      </div>
    </li>
  );
}
