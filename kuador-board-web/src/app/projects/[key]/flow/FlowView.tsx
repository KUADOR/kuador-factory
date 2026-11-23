"use client";

import { ArrowDown, ArrowRight, CalendarClock, Lock, Mail, Pencil, Plug, Settings2, User, Webhook } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { clsx } from "clsx";

import { setStagesAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ChipPicker } from "@/components/ui/ChipPicker";
import { Field, Switch, TextArea } from "@/components/ui/Field";
import { SidePanel } from "@/components/ui/SidePanel";
import { notify } from "@/components/ui/Toaster";
import type { MailInbox, Project, Routine, StageDef } from "@/lib/types";

type Option = { value: string; label: string };

/** Los pasos posteriores que las instrucciones nombran entre comillas («Ingresada»): los caminos según el resultado. */
function branches(stage: StageDef | undefined, later: { key: string; name: string }[]): { key: string; name: string }[] {
  const text = (stage?.instructions ?? "").toLowerCase();
  if (!text) return [];
  return later.filter((c) => text.includes(`«${c.name.toLowerCase()}»`) || text.includes(`"${c.name.toLowerCase()}"`));
}

export function FlowView({
  project,
  counts,
  agents,
  inboxes,
  routines,
  connections,
  roleOptions,
  roleName,
  canEdit,
}: {
  project: Project;
  counts: Record<string, number>;
  agents: { name: string; role: string }[];
  inboxes: MailInbox[];
  routines: Routine[];
  connections: { key: string; name: string; roles: string[]; writes: string[] }[];
  roleOptions: Option[];
  roleName: Record<string, string>;
  canEdit: boolean;
}) {
  const [editing, setEditing] = useState<string | null>(null);
  const columns = project.columns;
  const stageOf = (key: string) => project.stages.find((s) => s.column === key);
  const base = `/projects/${project.key}`;

  const responsible = (key: string, index: number) => {
    const st = stageOf(key);
    if (st?.gate) return { icon: Lock, text: st.approvers.length ? `Aprueba ${st.approvers.map((a) => roleName[a] ?? a).join(" o ")}` : "Aprueba una persona", tone: "gate" as const };
    if (st && st.roles.length > 0) {
      const names = st.roles.map((r) => agents.find((a) => a.role === r)?.name ?? roleName[r] ?? r);
      return { icon: null, text: names.join(", "), tone: "agent" as const };
    }
    if (index === 0) return { icon: null, text: "Entra el trabajo", tone: "edge" as const };
    if (columns[index].category === "DONE") return { icon: null, text: "Terminado", tone: "edge" as const };
    return { icon: User, text: "Una persona", tone: "person" as const };
  };

  const systemsFor = (key: string) => {
    const st = stageOf(key);
    if (!st || st.gate || st.roles.length === 0) return [];
    return connections.filter((c) => c.roles.length === 0 || c.roles.some((r) => st.roles.includes(r)));
  };

  const entries = [
    ...inboxes.map((b) => ({ icon: Mail, title: b.connectionName, detail: [b.enabled ? null : "en pausa", b.fromFilter ? `de ${b.fromFilter}` : null, b.onlyAttachments ? "con adjuntos" : null].filter(Boolean).join(" · ") || "cada correo nuevo", href: `${base}/settings/inbox` })),
    ...routines.map((r) => ({ icon: r.webhook ? Webhook : CalendarClock, title: r.title, detail: r.enabled ? r.schedule : "en pausa", href: `${base}/settings/routines` })),
  ];
  const outputs = connections.flatMap((c) => c.writes.map((w) => ({ name: w, system: c.name })));

  return (
    <div className="flex flex-col gap-6 px-4 py-5 sm:px-6 sm:py-6">
      <section className="flex flex-col gap-2">
        <h2 className="text-xs font-medium text-fg-muted">Entradas</h2>
        {entries.length === 0 ? (
          <p className="text-[13px] text-fg-faint">
            El trabajo entra a mano.{" "}
            {canEdit && (
              <Link href={`${base}/settings/inbox`} className="k-link">
                Recibir correos como tarjetas
              </Link>
            )}
          </p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {entries.map((e) => (
              <Link key={e.title + e.href} href={e.href} className="flex items-center gap-2 rounded-lg border border-line bg-surface px-3 py-2 text-[13px] hover:bg-hover">
                <e.icon className="size-4 shrink-0 text-sol-text" aria-hidden />
                <span className="min-w-0">
                  <span className="block font-medium text-fg-strong">{e.title}</span>
                  <span className="block text-xs text-fg-muted">{e.detail}</span>
                </span>
              </Link>
            ))}
          </div>
        )}
      </section>

      <section className="flex flex-col gap-2">
        <h2 className="text-xs font-medium text-fg-muted">Pasos</h2>
        <ol className="flex flex-col gap-1 lg:flex-row lg:items-stretch lg:gap-0 lg:overflow-x-auto lg:pb-2">
          {columns.map((c, i) => {
            const st = stageOf(c.key);
            const who = responsible(c.key, i);
            const systems = systemsFor(c.key);
            const ways = branches(st, columns.slice(i + 1).map((x) => ({ key: x.key, name: x.name })));
            const Icon = who.icon;
            return (
              <li key={c.key} className="flex flex-col items-center lg:flex-row">
                <button
                  type="button"
                  disabled={!canEdit || i === 0 || c.category === "DONE"}
                  onClick={() => setEditing(c.key)}
                  className={clsx(
                    "flex w-full flex-col gap-1.5 rounded-lg border bg-surface p-3 text-left lg:w-56 lg:shrink-0",
                    who.tone === "gate" ? "border-sol/50" : "border-line",
                    canEdit && i > 0 && c.category !== "DONE" ? "hover:border-line-strong hover:bg-hover" : "cursor-default",
                  )}
                >
                  <span className="flex items-baseline gap-2">
                    <span className="min-w-0 flex-1 text-[13px] font-medium text-fg-strong">{c.name}</span>
                    <span className="tabular rounded-full bg-hover px-1.5 text-[11px] text-fg-muted" title="Tarjetas en este paso">
                      {counts[c.key] ?? 0}
                    </span>
                  </span>
                  <span className={clsx("flex items-center gap-1 text-xs", who.tone === "agent" ? "text-fg" : "text-fg-muted")}>
                    {Icon && <Icon className="size-3 shrink-0" aria-hidden />}
                    {who.text}
                  </span>
                  {st?.instructions && <span className="line-clamp-3 text-xs text-fg-muted">{st.instructions}</span>}
                  {systems.length > 0 && (
                    <span className="flex flex-wrap gap-1">
                      {systems.map((s) => (
                        <span key={s.key} className="inline-flex items-center gap-1 rounded bg-hover px-1.5 py-px text-[11px] text-fg-muted">
                          <Plug className="size-3" aria-hidden />
                          {s.name}
                        </span>
                      ))}
                    </span>
                  )}
                  {(st?.checklist ?? []).length > 0 && <span className="text-[11px] text-fg-faint">Comprueba {(st?.checklist ?? []).length} punto(s)</span>}
                  {ways.length > 0 && (
                    <span className="flex flex-col gap-0.5 border-t border-line pt-1.5 text-[11px] text-fg-muted">
                      {ways.map((w) => (
                        <span key={w.key}>↳ puede pasar a «{w.name}»</span>
                      ))}
                    </span>
                  )}
                  {canEdit && i > 0 && c.category !== "DONE" && (
                    <span className="mt-auto flex items-center gap-1 pt-1 text-[11px] text-fg-faint">
                      <Pencil className="size-3" aria-hidden /> Editar
                    </span>
                  )}
                </button>
                {i < columns.length - 1 && (
                  <span className="flex items-center justify-center p-1 text-fg-faint" aria-hidden>
                    <ArrowDown className="size-4 lg:hidden" />
                    <ArrowRight className="hidden size-4 lg:block" />
                  </span>
                )}
              </li>
            );
          })}
        </ol>
      </section>

      <section className="flex flex-col gap-2">
        <h2 className="text-xs font-medium text-fg-muted">Salidas</h2>
        {outputs.length === 0 ? (
          <p className="text-[13px] text-fg-faint">Los agentes no cambian datos en otros sistemas: preparan y una persona hace.</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {outputs.map((o) => (
              <span key={o.system + o.name} className="flex items-center gap-2 rounded-lg border border-line bg-surface px-3 py-2 text-[13px]">
                <Plug className="size-4 shrink-0 text-sol-text" aria-hidden />
                <span>
                  <span className="font-mono text-xs text-fg-strong">{o.name}</span> <span className="text-fg-muted">en {o.system}</span>
                </span>
              </span>
            ))}
          </div>
        )}
      </section>

      {canEdit && (
        <p className="text-xs text-fg-faint">
          Para agregar, quitar u ordenar pasos:{" "}
          <Link href={`${base}/settings/columns`} className="k-link">
            <Settings2 className="mr-0.5 inline size-3" aria-hidden />
            Pasos y responsables
          </Link>
          .
        </p>
      )}

      <SidePanel open={editing != null} onClose={() => setEditing(null)} width="narrow" title={columns.find((c) => c.key === editing)?.name ?? "Paso"}>
        {editing && (
          <StepEditor
            key={editing}
            project={project}
            column={editing}
            roleOptions={roleOptions}
            onDone={() => setEditing(null)}
          />
        )}
      </SidePanel>
    </div>
  );
}

/** Editar un paso desde el flujo: quién lo hace, si es una aprobación, qué hace el agente y qué se comprueba. */
function StepEditor({ project, column, roleOptions, onDone }: { project: Project; column: string; roleOptions: Option[]; onDone: () => void }) {
  const router = useRouter();
  const current = project.stages.find((s) => s.column === column);
  const [roles, setRoles] = useState<string[]>(current?.roles ?? []);
  const [gate, setGate] = useState(current?.gate ?? false);
  const [instructions, setInstructions] = useState(current?.instructions ?? "");
  const [checklist, setChecklist] = useState((current?.checklist ?? []).join("\n"));
  const [pending, start] = useTransition();

  const save = () =>
    start(async () => {
      const edited: StageDef = {
        column,
        roles: gate ? [] : roles,
        gate,
        approvers: current?.approvers ?? [],
        rejectTo: current?.rejectTo ?? null,
        review: gate ? (current?.review ?? null) : null,
        autoPlan: gate ? false : (current?.autoPlan ?? false),
        checklist: checklist.split("\n").map((x) => x.trim()).filter(Boolean),
        requires: gate ? [] : (current?.requires ?? []),
        instructions: gate || roles.length === 0 ? null : instructions.trim() || null,
      };
      const others = project.stages.filter((s) => s.column !== column);
      const meaningful = edited.gate || edited.roles.length > 0 || (edited.checklist ?? []).length > 0 || (edited.requires ?? []).length > 0;
      const r = await setStagesAction(project.key, meaningful ? [...others, edited] : others, project.maxRework);
      if (!r.ok) {
        notify.error("No se pudo guardar", r.error);
        return;
      }
      notify.ok("Paso guardado");
      onDone();
      router.refresh();
    });

  return (
    <form className="flex flex-col gap-4 p-5" onSubmit={(e) => { e.preventDefault(); save(); }}>
      <Switch checked={gate} onChange={setGate} label="Aquí aprueba una persona antes de seguir" />
      {!gate && (
        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium text-fg-muted">Quién lo hace</span>
          <ChipPicker value={roles} options={roleOptions} onChange={setRoles} placeholder="Agregar responsable…" ariaLabel="Quién hace este paso" empty="una persona del equipo" />
        </div>
      )}
      {!gate && roles.length > 0 && (
        <Field label="Qué hace el agente en este paso" hint="Qué mirar, qué sistema usar y qué hacer si algo no cuadra. Puedes decir a qué paso va según el resultado: «Ingresada» o «Desfase».">
          {(id) => <TextArea id={id} rows={7} maxLength={3000} value={instructions} onChange={(e) => setInstructions(e.target.value)} />}
        </Field>
      )}
      <Field label={gate ? "Qué comprueba quien aprueba (uno por línea)" : "Qué se comprueba antes de entregar (uno por línea)"}>
        {(id) => <TextArea id={id} rows={3} value={checklist} onChange={(e) => setChecklist(e.target.value)} />}
      </Field>
      <div className="flex justify-end">
        <Button type="submit" tone="primary" loading={pending}>
          Guardar paso
        </Button>
      </div>
    </form>
  );
}
