"use client";

import { Check, Cloud, Laptop, LoaderCircle, Pencil, Sparkles, Target, Wand2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { clsx } from "clsx";

import { draftApproveAction, draftAskAction, draftJobAction } from "@/app/actions";
import { Markdown } from "@/components/docs/Markdown";
import { Button } from "@/components/ui/Button";
import { DictateButton } from "@/components/ui/DictateButton";
import { TextArea, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { DraftInput, DraftJob, DraftPlan, DraftStep } from "@/lib/types";

type Doc = { kind: string; label: string; content: string };
const QUICK = ["Más corto", "Más concreto", "Agrega ejemplos", "Menos técnico"];
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * Redactar con IA los documentos base del tablero (o, fuera de software, su objetivo): la idea central, unas pocas
 * preguntas, los borradores con las plantillas de la metodología y el pulido conversando. Trabaja en el servidor (clave de
 * la empresa) o en tu computador (tu runner con tu suscripción), y al aprobar queda todo como versión 1.
 */
export function DraftingAssistant({ projectKey, plan, machineOnline }: { projectKey: string; plan: DraftPlan; machineOnline: boolean }) {
  const router = useRouter();
  const software = plan.projectKind === "SOFTWARE";
  const [local, setLocal] = useState(!plan.serverReady);
  const [idea, setIdea] = useState("");
  const [kinds, setKinds] = useState(plan.documents.filter((d) => d.suggested).map((d) => d.kind));
  const [questions, setQuestions] = useState<{ id: string; text: string; options: string[] }[] | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [docs, setDocs] = useState<Doc[] | null>(null);
  const [goal, setGoal] = useState(plan.goal ?? "");
  const [tab, setTab] = useState<string>("GOAL");
  const [editing, setEditing] = useState(false);
  const [instruction, setInstruction] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const cancelled = useRef(false);

  /** Un paso: en el servidor responde ya; en tu computador se espera a que tu runner lo resuelva. */
  const run = async (step: DraftStep, input: DraftInput, label: string): Promise<DraftJob["result"] | null> => {
    setBusy(label);
    cancelled.current = false;
    try {
      const first = await draftAskAction(projectKey, step, input, local);
      if (!first.ok) {
        notify.error("No se pudo", first.error);
        return null;
      }
      let job = first.data;
      const started = Date.now();
      while (job.status === "PENDING" || job.status === "RUNNING") {
        if (cancelled.current) return null;
        await sleep(2000);
        setBusy(`${label} · tu computador está trabajando (${Math.round((Date.now() - started) / 1000)} s)`);
        const next = await draftJobAction(job.id!);
        if (!next.ok) {
          notify.error("No se pudo", next.error);
          return null;
        }
        job = next.data;
      }
      if (job.status === "FAILED") {
        notify.error("La IA no pudo", job.error ?? undefined);
        return null;
      }
      return job.result;
    } finally {
      setBusy(null);
    }
  };

  const askQuestions = async () => {
    const r = await run("questions", { idea, kinds }, "Pensando qué preguntarte");
    if (r) setQuestions(r.questions ?? []);
  };

  const draft = async () => {
    const r = await run("draft", { idea, answers, kinds }, software ? "Redactando los documentos" : "Escribiendo el objetivo");
    if (!r) return;
    setDocs(r.documents ?? []);
    if (r.goal) setGoal(r.goal);
    setTab(r.documents?.[0]?.kind ?? "GOAL");
    setEditing(false);
  };

  const refine = async (text: string) => {
    const current = tab === "GOAL" ? goal : (docs?.find((d) => d.kind === tab)?.content ?? "");
    const r = await run("refine", { idea, kind: tab, content: current, instruction: text }, "Aplicando tu cambio");
    if (!r?.content) return;
    if (tab === "GOAL") setGoal(r.content);
    else setDocs((all) => (all ?? []).map((d) => (d.kind === tab ? { ...d, content: r.content! } : d)));
    setInstruction("");
    notify.ok("Cambio aplicado");
  };

  const approve = async () => {
    setBusy("Guardando");
    const r = await draftApproveAction(projectKey, (docs ?? []).map((d) => ({ kind: d.kind, content: d.content })), goal.trim() || null);
    setBusy(null);
    if (!r.ok) return notify.error("No se pudo guardar", r.error);
    notify.ok(software ? `Guardados ${r.data.saved.length} documentos y el objetivo` : "Objetivo guardado", "Los agentes los leen desde su próxima tarjeta.");
    router.push(software ? `/projects/${projectKey}/docs` : `/projects/${projectKey}/settings/general`);
  };

  const current = tab === "GOAL" ? null : docs?.find((d) => d.kind === tab);
  const stage = docs ? 3 : questions ? 2 : 1;

  return (
    <div className="flex max-w-3xl flex-col gap-5">
      {/* Dónde trabaja la IA: el servidor (clave de la empresa) o tu computador (tu suscripción, sin gastar la clave). */}
      <div className="flex flex-col gap-2 rounded-lg border border-line bg-surface p-4">
        <span className="text-[13px] font-medium text-fg-strong">Dónde trabaja la IA</span>
        <div className="grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label="Dónde trabaja la IA">
          <ModeCard
            active={!local}
            disabled={!plan.serverReady}
            icon={Cloud}
            title="En el servidor"
            hint={plan.serverReady ? "Al instante, con la clave de IA de la empresa." : "El espacio no tiene clave de IA (Conexiones → IA)."}
            onPick={() => setLocal(false)}
          />
          <ModeCard
            active={local}
            icon={Laptop}
            title="En mi computador"
            hint={machineOnline ? "Tu runner con tu suscripción: no gasta la clave." : "Necesita tu runner prendido (Agentes locales)."}
            onPick={() => setLocal(true)}
          />
        </div>
      </div>

      {/* 1. La idea central. */}
      <section className={clsx("flex flex-col gap-3 rounded-lg border bg-surface p-4", stage === 1 ? "border-sol/50" : "border-line")}>
        <h2 className="text-[14px] font-semibold text-fg-strong">1 · La idea central</h2>
        <p className="-mt-2 text-xs text-fg-muted">De qué se trata, para quién y qué es lo más importante. Dos o tres frases bastan; puedes dictarla.</p>
        <div className="flex items-start gap-2">
          <TextArea
            rows={4}
            value={idea}
            onChange={(e) => setIdea(e.target.value)}
            placeholder={software ? "Una app para que la clínica agende citas en línea, simple para pacientes mayores, con recordatorios por WhatsApp." : "Publicar 3 veces por semana para que pymes de la ciudad nos conozcan y pidan una demo."}
            aria-label="Idea central"
          />
          <DictateButton onText={(t) => setIdea((v) => (v ? `${v} ${t}` : t))} />
        </div>
        {software && plan.documents.length > 0 && (
          <fieldset className="flex flex-col gap-1.5">
            <legend className="mb-1 text-xs font-medium text-fg-muted">Qué documentos redactar</legend>
            {plan.documents.map((d) => (
              <label key={d.kind} className="flex items-start gap-2 text-[13px] text-fg">
                <input
                  type="checkbox"
                  className="mt-0.5"
                  checked={kinds.includes(d.kind)}
                  onChange={(e) => setKinds((k) => (e.target.checked ? [...k, d.kind] : k.filter((x) => x !== d.kind)))}
                />
                <span>
                  <span className="font-medium text-fg-strong">{d.label}</span>
                  {!d.suggested && <span className="text-fg-faint"> · opcional</span>}
                  {d.purpose && <span className="block text-xs text-fg-muted">{d.purpose}</span>}
                </span>
              </label>
            ))}
          </fieldset>
        )}
        {stage === 1 && (
          <div className="flex flex-wrap gap-2">
            <Button tone="primary" icon={Sparkles} loading={busy != null} disabled={!idea.trim() || (software && kinds.length === 0)} onClick={askQuestions}>
              Siguiente
            </Button>
            <Button tone="ghost" disabled={busy != null || !idea.trim() || (software && kinds.length === 0)} onClick={draft}>
              Saltar las preguntas
            </Button>
          </div>
        )}
      </section>

      {/* 2. Las preguntas: solo lo que falta, con opciones para tocar. */}
      {questions && (
        <section className={clsx("flex flex-col gap-4 rounded-lg border bg-surface p-4", stage === 2 ? "border-sol/50" : "border-line")}>
          <h2 className="text-[14px] font-semibold text-fg-strong">2 · Unas preguntas</h2>
          {questions.length === 0 && <p className="text-[13px] text-fg-muted">Con tu idea alcanza: no hace falta preguntar nada.</p>}
          {questions.map((q) => (
            <div key={q.id} className="flex flex-col gap-2">
              <p className="text-[13px] font-medium text-fg-strong">{q.text}</p>
              <div className="flex flex-wrap gap-1.5">
                {q.options.map((o) => (
                  <button
                    key={o}
                    type="button"
                    aria-pressed={answers[q.text] === o}
                    onClick={() => setAnswers((a) => ({ ...a, [q.text]: o }))}
                    className={clsx(
                      "rounded-full border px-3 py-1.5 text-[13px]",
                      answers[q.text] === o ? "border-sol bg-sol/15 font-medium text-fg-strong" : "border-line text-fg-muted hover:bg-hover",
                    )}
                  >
                    {o}
                  </button>
                ))}
              </div>
              <TextInput
                size="sm"
                value={q.options.includes(answers[q.text] ?? "") ? "" : (answers[q.text] ?? "")}
                onChange={(e) => setAnswers((a) => ({ ...a, [q.text]: e.target.value }))}
                placeholder="Otra respuesta…"
                aria-label={`Otra respuesta: ${q.text}`}
              />
            </div>
          ))}
          {stage === 2 && (
            <Button tone="primary" icon={Wand2} loading={busy != null} onClick={draft} className="self-start">
              {software ? "Redactar los documentos" : "Proponer el objetivo"}
            </Button>
          )}
        </section>
      )}

      {/* 3. Los borradores: ver, pulir conversando y aprobar. */}
      {docs && (
        <section className="flex flex-col gap-3 rounded-lg border border-sol/50 bg-surface p-4">
          <h2 className="text-[14px] font-semibold text-fg-strong">3 · Revisa y pule</h2>
          <div className="flex gap-1.5 overflow-x-auto" role="tablist" aria-label="Documentos">
            {[...docs.map((d) => ({ key: d.kind, label: d.label })), { key: "GOAL", label: "Objetivo del tablero" }].map((t) => (
              <button
                key={t.key}
                type="button"
                role="tab"
                aria-selected={tab === t.key}
                onClick={() => {
                  setTab(t.key);
                  setEditing(false);
                }}
                className={clsx(
                  "flex h-8 shrink-0 items-center gap-1.5 rounded-full border px-3 text-[13px] whitespace-nowrap",
                  tab === t.key ? "border-sol/60 bg-sol/15 font-medium text-fg-strong" : "border-line text-fg-muted",
                )}
              >
                {t.key === "GOAL" && <Target className="size-3.5" aria-hidden />}
                {t.label}
              </button>
            ))}
          </div>

          {tab === "GOAL" ? (
            <TextArea rows={3} maxLength={1000} value={goal} onChange={(e) => setGoal(e.target.value)} aria-label="Objetivo del tablero" />
          ) : current && editing ? (
            <TextArea
              rows={18}
              value={current.content}
              onChange={(e) => setDocs((all) => (all ?? []).map((d) => (d.kind === tab ? { ...d, content: e.target.value } : d)))}
              className="font-mono text-[13px]"
              aria-label={current.label}
            />
          ) : current ? (
            <div className="max-h-[28rem] overflow-y-auto rounded-md border border-line bg-page px-4 py-3">
              <Markdown content={current.content} />
            </div>
          ) : null}
          {current && (
            <Button size="sm" tone="ghost" icon={Pencil} className="self-start" onClick={() => setEditing((e) => !e)}>
              {editing ? "Ver formateado" : "Editar a mano"}
            </Button>
          )}

          {/* Pulir conversando: un pedido en lenguaje natural o un toque rápido. */}
          <form
            className="flex flex-col gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (instruction.trim()) void refine(instruction.trim());
            }}
          >
            <div className="flex flex-wrap gap-1.5">
              {QUICK.map((q) => (
                <button key={q} type="button" disabled={busy != null} onClick={() => void refine(q)} className="rounded-full border border-line px-3 py-1 text-xs text-fg-muted hover:bg-hover">
                  {q}
                </button>
              ))}
            </div>
            <div className="flex items-start gap-2">
              <TextInput value={instruction} onChange={(e) => setInstruction(e.target.value)} placeholder="Pídele un cambio: «agrega que también lo usan las enfermeras»" aria-label="Cambio" />
              <DictateButton onText={(t) => setInstruction((v) => (v ? `${v} ${t}` : t))} />
              <Button type="submit" loading={busy != null} disabled={!instruction.trim()}>
                Aplicar
              </Button>
            </div>
          </form>

          <div className="flex flex-wrap items-center gap-2 border-t border-line pt-3">
            <Button tone="primary" icon={Check} loading={busy === "Guardando"} disabled={busy != null} onClick={approve}>
              {software ? "Aprobar y guardar" : "Guardar el objetivo"}
            </Button>
            <Button tone="ghost" disabled={busy != null} onClick={draft}>
              Volver a redactar
            </Button>
            <span className="text-xs text-fg-faint">{software ? "Quedan como versión 1 en Documentos; puedes cambiarlos cuando quieras." : ""}</span>
          </div>
        </section>
      )}

      {busy && busy !== "Guardando" && (
        <div role="status" className="sticky bottom-3 flex items-center gap-2 self-center rounded-full border border-line bg-surface px-4 py-2 text-[13px] text-fg shadow-float">
          <LoaderCircle className="size-4 animate-spin text-sol-text" aria-hidden />
          {busy}…
          {local && (
            <button type="button" onClick={() => (cancelled.current = true)} className="ml-2 text-xs text-fg-faint hover:text-fg-strong">
              Dejar de esperar
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function ModeCard({
  active,
  disabled,
  icon: Icon,
  title,
  hint,
  onPick,
}: {
  active: boolean;
  disabled?: boolean;
  icon: typeof Cloud;
  title: string;
  hint: string;
  onPick: () => void;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={active}
      disabled={disabled}
      onClick={onPick}
      className={clsx(
        "flex items-start gap-2.5 rounded-lg border px-3 py-2.5 text-left disabled:opacity-50",
        active ? "border-sol bg-sol/10" : "border-line hover:bg-hover",
      )}
    >
      <Icon className={clsx("mt-0.5 size-4 shrink-0", active ? "text-sol-text" : "text-fg-muted")} aria-hidden />
      <span>
        <span className="block text-[13px] font-medium text-fg-strong">{title}</span>
        <span className="block text-xs text-fg-muted">{hint}</span>
      </span>
    </button>
  );
}
