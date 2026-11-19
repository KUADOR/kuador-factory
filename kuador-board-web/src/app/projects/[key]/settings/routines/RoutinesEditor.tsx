"use client";

import { CalendarClock, Copy, Pencil, Play, Plus, Trash, Webhook } from "lucide-react";
import Link from "next/link";
import { useState, useTransition } from "react";
import { clsx } from "clsx";

import { deleteRoutineAction, routineWebhookAction, runRoutineAction, saveRoutineAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Field, SelectInput, Switch, TextArea, TextInput } from "@/components/ui/Field";
import { SidePanel } from "@/components/ui/SidePanel";
import { notify } from "@/components/ui/Toaster";
import type { Routine, RoutineFrequency, RoutineInput } from "@/lib/types";

const DAYS = ["L", "M", "X", "J", "V", "S", "D"];
const DAY_NAMES = ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"];

const when = (iso: string | null) =>
  iso ? new Date(iso).toLocaleString("es", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "—";

const empty = (): RoutineInput => ({
  title: "",
  description: null,
  status: null,
  assigneeId: null,
  frequency: "WEEKLY",
  weekdays: "1",
  monthDay: 1,
  at: "08:00",
  timezone: typeof Intl !== "undefined" ? Intl.DateTimeFormat().resolvedOptions().timeZone : "America/Guayaquil",
  enabled: true,
  skipIfOpen: true,
});

/**
 * Rutinas del tablero: trabajo que se repite. A su hora el tablero crea la tarjeta y se la da a quien elijas (un agente
 * la trabaja solo); también se disparan desde afuera con su webhook (un formulario de la web, otra app).
 */
export function RoutinesEditor({
  projectKey,
  routines,
  columns,
  assignees,
  apiBase,
}: {
  projectKey: string;
  routines: Routine[];
  columns: { key: string; name: string }[];
  assignees: { id: string; name: string; agent: boolean }[];
  apiBase: string;
}) {
  const [editing, setEditing] = useState<{ id: string | null; input: RoutineInput } | null>(null);
  const [secret, setSecret] = useState<{ id: string; secret: string } | null>(null);
  const [pending, start] = useTransition();

  const edit = (r: Routine) =>
    setEditing({
      id: r.id,
      input: {
        title: r.title,
        description: r.description,
        status: r.status,
        assigneeId: r.assigneeId,
        frequency: r.frequency,
        weekdays: r.weekdays,
        monthDay: r.monthDay,
        at: r.at,
        timezone: r.timezone,
        enabled: r.enabled,
        skipIfOpen: r.skipIfOpen,
      },
    });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex justify-end">
        <Button tone="primary" size="sm" icon={Plus} onClick={() => setEditing({ id: null, input: empty() })}>
          Nueva rutina
        </Button>
      </div>

      {routines.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-line-strong px-6 py-10 text-center">
          <CalendarClock className="size-6 text-fg-faint" aria-hidden />
          <p className="text-[14px] font-medium text-fg-strong">Todavía no hay rutinas</p>
        </div>
      ) : (
        <ul className="flex flex-col gap-2.5">
          {routines.map((r) => (
            <li key={r.id} className={clsx("flex flex-col gap-2.5 rounded-lg border border-line bg-surface px-4 py-3", !r.enabled && "opacity-70")}>
              <div className="flex flex-wrap items-start gap-x-3 gap-y-1">
                <span className="min-w-0 flex-1">
                  <span className="block text-[14px] font-medium text-fg-strong">{r.title}</span>
                  <span className="block text-xs text-fg-muted">
                    {r.schedule} · para {r.assigneeName ?? "nadie (sin asignar)"}
                  </span>
                </span>
                <span className="text-xs text-fg-faint">{r.enabled ? `Próxima: ${when(r.nextRunAt)}` : "En pausa"}</span>
              </div>
              {r.lastIssueKey && (
                <p className="text-xs text-fg-muted">
                  Última:{" "}
                  <Link href={`/projects/${projectKey}?issue=${r.lastIssueKey}`} className="k-link font-mono">
                    {r.lastIssueKey}
                  </Link>{" "}
                  · {when(r.lastRunAt)}
                </p>
              )}
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  icon={Play}
                  loading={pending}
                  onClick={() =>
                    start(async () => {
                      const res = await runRoutineAction(projectKey, r.id);
                      if (!res.ok) notify.error("No se pudo ejecutar", res.error);
                      else if (res.data.skipped) notify.ok("No se creó otra", res.data.skipped);
                      else notify.ok(`Creada ${res.data.issueKey}`, r.title);
                    })
                  }
                >
                  Ejecutar ahora
                </Button>
                <Button size="sm" tone="ghost" icon={Pencil} onClick={() => edit(r)}>
                  Editar
                </Button>
                <Button
                  size="sm"
                  tone="ghost"
                  icon={Webhook}
                  disabled={pending}
                  onClick={() =>
                    start(async () => {
                      if (r.webhook && !window.confirm("Ya tiene un webhook: si generas otro, el anterior deja de funcionar. ¿Seguir?")) return;
                      const res = await routineWebhookAction(projectKey, r.id);
                      if (res.ok) setSecret({ id: r.id, secret: res.data.secret });
                      else notify.error("No se pudo crear el webhook", res.error);
                    })
                  }
                >
                  {r.webhook ? "Cambiar webhook" : "Webhook"}
                </Button>
                <Button
                  size="sm"
                  tone="ghost"
                  icon={Trash}
                  iconOnly
                  label={`Borrar ${r.title}`}
                  disabled={pending}
                  onClick={() =>
                    start(async () => {
                      if (!window.confirm(`¿Borrar la rutina «${r.title}»? Las tarjetas que ya creó se quedan.`)) return;
                      const res = await deleteRoutineAction(projectKey, r.id);
                      if (res.ok) notify.ok("Rutina borrada");
                      else notify.error("No se pudo borrar", res.error);
                    })
                  }
                />
              </div>
            </li>
          ))}
        </ul>
      )}

      <SidePanel open={editing != null} onClose={() => setEditing(null)} width="narrow" title={editing?.id ? "Editar rutina" : "Nueva rutina"}>
        {editing && (
          <RoutineForm
            key={editing.id ?? "new"}
            initial={editing.input}
            columns={columns}
            assignees={assignees}
            onSave={(input) =>
              start(async () => {
                const res = await saveRoutineAction(projectKey, editing.id, input);
                if (res.ok) {
                  notify.ok(editing.id ? "Rutina actualizada" : "Rutina creada", res.data.schedule);
                  setEditing(null);
                } else notify.error("No se pudo guardar", res.error);
              })
            }
            pending={pending}
          />
        )}
      </SidePanel>

      <Dialog open={secret != null} onClose={() => setSecret(null)} title="Webhook de la rutina">
        {secret && <WebhookInfo url={`${apiBase}/api/routines/${secret.id}/hook`} secret={secret.secret} />}
      </Dialog>
    </div>
  );
}

function RoutineForm({
  initial,
  columns,
  assignees,
  onSave,
  pending,
}: {
  initial: RoutineInput;
  columns: { key: string; name: string }[];
  assignees: { id: string; name: string; agent: boolean }[];
  onSave: (input: RoutineInput) => void;
  pending: boolean;
}) {
  const [v, setV] = useState(initial);
  const set = <K extends keyof RoutineInput>(k: K, value: RoutineInput[K]) => setV((x) => ({ ...x, [k]: value }));
  const days = new Set((v.weekdays ?? "").split(",").filter(Boolean).map(Number));
  const toggleDay = (n: number) => {
    const next = new Set(days);
    if (next.has(n)) next.delete(n);
    else next.add(n);
    set("weekdays", [...next].sort().join(","));
  };
  return (
    <form
      className="flex flex-col gap-4 p-5"
      onSubmit={(e) => {
        e.preventDefault();
        onSave({ ...v, description: v.description?.trim() || null });
      }}
    >
      <Field label="Título de la tarjeta" hint="{fecha} se cambia por el día.">
        {(id) => <TextInput id={id} required maxLength={200} value={v.title} onChange={(e) => set("title", e.target.value)} placeholder="Seguimiento de prospectos · {fecha}" />}
      </Field>
      <Field label="Qué hay que hacer">
        {(id) => <TextArea id={id} rows={4} value={v.description ?? ""} onChange={(e) => set("description", e.target.value)} />}
      </Field>
      <Field label="Cada cuánto">
        {(id) => (
          <SelectInput<RoutineFrequency>
            id={id}
            value={v.frequency}
            onChange={(f) => f && set("frequency", f)}
            options={[
              { value: "DAILY", label: "Todos los días" },
              { value: "WEEKLY", label: "Ciertos días de la semana" },
              { value: "MONTHLY", label: "Una vez al mes" },
            ]}
          />
        )}
      </Field>
      {v.frequency === "WEEKLY" && (
        <div className="flex flex-col gap-1.5">
          <span className="text-[13px] font-medium text-fg-strong">Días</span>
          <div className="flex gap-1.5" role="group" aria-label="Días de la semana">
            {DAYS.map((d, i) => (
              <button
                key={d + i}
                type="button"
                aria-pressed={days.has(i + 1)}
                aria-label={DAY_NAMES[i]}
                onClick={() => toggleDay(i + 1)}
                className={clsx(
                  "grid size-9 place-items-center rounded-full border text-[13px] font-medium",
                  days.has(i + 1) ? "border-sol bg-sol text-on-sol" : "border-line text-fg-muted hover:bg-hover",
                )}
              >
                {d}
              </button>
            ))}
          </div>
        </div>
      )}
      {v.frequency === "MONTHLY" && (
        <Field label="Día del mes" hint="Si el mes es más corto, corre su último día.">
          {(id) => (
            <TextInput id={id} type="number" min={1} max={31} value={String(v.monthDay ?? 1)} onChange={(e) => set("monthDay", Number(e.target.value))} />
          )}
        </Field>
      )}
      <Field label="Hora" hint={`Zona horaria: ${v.timezone}`}>
        {(id) => <TextInput id={id} type="time" required value={v.at} onChange={(e) => set("at", e.target.value)} />}
      </Field>
      <Field label="Columna donde entra">
        {(id) => (
          <SelectInput<string>
            id={id}
            value={v.status}
            placeholder="La primera del tablero"
            onChange={(s) => set("status", s ?? null)}
            options={columns.map((c) => ({ value: c.key, label: c.name }))}
          />
        )}
      </Field>
      <Field label="Para quién" hint="Si es un agente, la trabaja solo.">
        {(id) => (
          <SelectInput<string>
            id={id}
            value={v.assigneeId}
            placeholder="Sin asignar"
            onChange={(a) => set("assigneeId", a ?? null)}
            options={assignees.map((a) => ({ value: a.id, label: `${a.name}${a.agent ? " · agente" : ""}` }))}
          />
        )}
      </Field>
      <Switch checked={v.skipIfOpen} onChange={(c) => set("skipIfOpen", c)} label="No crear otra si la anterior sigue abierta" />
      <Switch checked={v.enabled} onChange={(c) => set("enabled", c)} label="Activa" />
      <div className="flex justify-end">
        <Button type="submit" tone="primary" loading={pending} disabled={!v.title.trim() || (v.frequency === "WEEKLY" && days.size === 0)}>
          Guardar rutina
        </Button>
      </div>
    </form>
  );
}

function WebhookInfo({ url, secret }: { url: string; secret: string }) {
  const curl = `curl -X POST ${url} \\\n  -H "X-Kuador-Secret: ${secret}" \\\n  -H "Content-Type: application/json" \\\n  -d '{"text": "Nuevo prospecto: Clínica Norte"}'`;
  return (
    <div className="flex flex-col gap-3 text-[13px]">
      <p className="text-fg-muted">Guárdalo ahora: el secreto se muestra una sola vez.</p>
      <div className="relative">
        <pre className="overflow-x-auto rounded-md border border-line bg-sunken px-3 py-2.5 pr-10 font-mono text-[12px] whitespace-pre text-fg-strong">{curl}</pre>
        <span className="absolute top-1.5 right-1.5">
          <Button
            size="sm"
            tone="ghost"
            icon={Copy}
            iconOnly
            label="Copiar"
            onClick={async () => {
              await navigator.clipboard.writeText(curl);
              notify.ok("Copiado");
            }}
          />
        </span>
      </div>
    </div>
  );
}
