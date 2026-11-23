"use client";

import { Lock, Sparkles } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { createDesignedBoardAction, designBoardAction, designQuestionsAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextArea, TextInput } from "@/components/ui/Field";
import { FIELD_KIND_LABEL } from "@/lib/labels";
import type { BoardDesign, DesignAnswer, DesignQuestion } from "@/lib/types";

/**
 * La persona cuenta a qué se dedica su empresa y qué trabajo quiere ordenar; la IA propone los pasos, quién hace cada
 * uno, dónde aprueba una persona y qué datos lleva cada tarjeta. Nada se crea hasta que ella lo acepta.
 */
export function BoardDesigner({
  roles,
  systems = [],
  aiReady,
}: {
  roles: { key: string; name: string }[];
  /** Los sistemas conectados, para nombrarlos en la propuesta. */
  systems?: { key: string; name: string }[];
  aiReady: boolean;
}) {
  const router = useRouter();
  const [description, setDescription] = useState("");
  const [design, setDesign] = useState<BoardDesign | null>(null);
  // Las preguntas del asistente antes de proponer (null = todavía no se pidieron) y lo que la persona va respondiendo.
  const [questions, setQuestions] = useState<DesignQuestion[] | null>(null);
  const [picked, setPicked] = useState<Record<string, string[]>>({});
  const [other, setOther] = useState<Record<string, string>>({});
  const [change, setChange] = useState("");
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  // Las acciones que cambian datos que la persona deja habilitar al crear (vienen todas marcadas: las propuso para el trabajo).
  const [writes, setWrites] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const roleName = (k: string) => roles.find((r) => r.key === k)?.name ?? k;
  const systemName = (k: string) => systems.find((x) => x.key === k)?.name ?? k;

  /** Lo respondido, en palabras: las opciones elegidas y lo que se escribió. */
  const answers = (): DesignAnswer[] =>
    (questions ?? [])
      .map((q) => {
        const labels = (picked[q.id] ?? []).map((v) => q.options.find((o) => o.value === v)?.label ?? v);
        const text = (other[q.id] ?? "").trim();
        return { question: q.text, answer: [...labels, ...(text ? [text] : [])].join("; ") };
      })
      .filter((a) => a.answer);

  const show = (d: BoardDesign) => {
    setDesign(d);
    setWrites(d.writes ?? []);
    setKey(d.key);
    setName(d.name);
    setError(null);
  };

  /** Primero las preguntas; si el asistente no tiene nada que preguntar, propone directo. */
  const ask = () =>
    start(async () => {
      const q = await designQuestionsAction(description);
      if (!q.ok) return setError(q.error);
      setError(null);
      if (q.data.questions.length === 0) {
        const r = await designBoardAction({ description });
        if (!r.ok) return setError(r.error);
        return show(r.data);
      }
      setQuestions(q.data.questions);
    });

  const propose = () =>
    start(async () => {
      const r = await designBoardAction({ description, answers: answers() });
      if (!r.ok) return setError(r.error);
      show(r.data);
    });

  /** Ajusta la propuesta con lo que la persona pide cambiar. */
  const refine = () =>
    start(async () => {
      if (!design || !change.trim()) return;
      const r = await designBoardAction({ description, answers: answers(), previous: design, change: change.trim() });
      if (!r.ok) return setError(r.error);
      setChange("");
      show(r.data);
    });

  const toggle = (q: DesignQuestion, value: string) =>
    setPicked((all) => {
      const current = all[q.id] ?? [];
      const next = q.kind === "MULTI" ? (current.includes(value) ? current.filter((v) => v !== value) : [...current, value]) : [value];
      return { ...all, [q.id]: next };
    });

  const create = () =>
    start(async () => {
      if (!design) return;
      const r = await createDesignedBoardAction({ ...design, writes }, key, name);
      if (!r.ok) return setError(r.error);
      router.push(`/projects/${r.data.key}/setup`);
    });

  if (!aiReady) {
    return (
      <p className="px-4 py-5 text-[13px] text-fg-muted sm:px-6">
        Primero conecta la IA de tu empresa en{" "}
        <Link href="/connections" className="k-link">
          Conexiones
        </Link>
        .
      </p>
    );
  }

  if (!design && questions) {
    return (
      <form className="flex flex-col gap-5 px-4 py-5 sm:px-6" onSubmit={(e) => { e.preventDefault(); propose(); }}>
        <p className="text-[13px] text-fg-muted">Antes de armarlo, unas preguntas para que el tablero quede conectado y trabaje solo donde se pueda.</p>
        {questions.map((q, i) => (
          <fieldset key={q.id} className="flex flex-col gap-2">
            <legend className="text-[13px] font-medium text-fg-strong">
              {i + 1}. {q.text}
            </legend>
            {q.help && <p className="text-xs text-fg-muted">{q.help}</p>}
            {q.options.length > 0 && (
              <div className="flex flex-col gap-1">
                {q.options.map((o) => (
                  <label key={o.value} className="flex cursor-pointer items-start gap-2 rounded-md px-2 py-1.5 text-[13px] text-fg hover:bg-hover">
                    <input
                      type={q.kind === "MULTI" ? "checkbox" : "radio"}
                      name={q.id}
                      className="mt-0.5"
                      checked={(picked[q.id] ?? []).includes(o.value)}
                      onChange={() => toggle(q, o.value)}
                    />
                    {o.label}
                  </label>
                ))}
              </div>
            )}
            <TextInput
              aria-label={`Otra respuesta para: ${q.text}`}
              placeholder={q.options.length > 0 ? "Otra respuesta (opcional)" : "Tu respuesta"}
              maxLength={500}
              value={other[q.id] ?? ""}
              onChange={(e) => setOther((all) => ({ ...all, [q.id]: e.target.value }))}
            />
          </fieldset>
        ))}
        {error && (
          <p role="alert" className="text-[13px] text-danger">
            {error}
          </p>
        )}
        <div className="flex flex-wrap justify-end gap-2">
          <Button tone="ghost" disabled={pending} onClick={() => { setQuestions(null); setError(null); }}>
            Cambiar la descripción
          </Button>
          <Button type="submit" tone="primary" icon={Sparkles} loading={pending}>
            Proponer tablero
          </Button>
        </div>
      </form>
    );
  }

  if (!design) {
    return (
      <form className="flex flex-col gap-4 px-4 py-5 sm:px-6" onSubmit={(e) => { e.preventDefault(); ask(); }}>
        <Field label="¿A qué se dedica tu empresa y qué trabajo quieres ordenar?" error={error}>
          {(id) => (
            <TextArea
              id={id}
              rows={7}
              required
              maxLength={3000}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Somos una clínica dental con tres doctores. Quiero ordenar los pedidos de cita: revisar la historia del paciente, confirmar la hora, atender y facturar."
              autoFocus
            />
          )}
        </Field>
        <div className="flex justify-end">
          <Button type="submit" tone="primary" icon={Sparkles} loading={pending} disabled={description.trim().length < 20}>
            Siguiente
          </Button>
        </div>
      </form>
    );
  }

  const t = design.template;
  const card = t.issueTypes.find((x) => x.type !== "EPIC");
  return (
    <form className="flex flex-col gap-5 px-4 py-5 sm:px-6" onSubmit={(e) => { e.preventDefault(); create(); }}>
      {design.summary && <p className="text-[13px] leading-relaxed text-fg">{design.summary}</p>}
      <ol className="flex flex-col divide-y divide-line rounded-lg border border-line bg-surface">
        {t.columns.map((c, i) => {
          const stage = t.stages.find((s) => s.column === c.key);
          return (
            <li key={c.key} className="flex flex-col gap-1 px-5 py-3">
              <div className="flex items-baseline gap-3">
                <span className="tabular w-4 font-mono text-xs text-fg-faint">{i + 1}</span>
                <span className="min-w-0 flex-1 text-[13px] font-medium text-fg-strong">{c.name}</span>
                <span className="flex items-center gap-1 text-xs text-fg-muted">
                  {stage?.gate && <Lock className="size-3 text-sol-text" aria-hidden />}
                  {stage?.gate ? "Aprueba una persona" : stage && stage.roles.length > 0 ? stage.roles.map(roleName).join(", ") : i === 0 ? "Entra el pedido" : i === t.columns.length - 1 ? "Terminado" : "Una persona"}
                </span>
              </div>
              {stage?.instructions && <p className="pl-7 text-xs whitespace-pre-line text-fg">{stage.instructions}</p>}
              {i === 0 && design.mailbox && (
                <p className="pl-7 text-xs text-fg-muted">Los correos que lleguen a «{systemName(design.mailbox)}» entran aquí solos, como tarjetas.</p>
              )}
              {(stage?.checklist ?? []).length > 0 && <p className="pl-7 text-xs text-fg-muted">Comprueba: {(stage?.checklist ?? []).join(" · ")}</p>}
              {(stage?.requires ?? []).some((r) => r.kind === "ATTACHMENT") && <p className="pl-7 text-xs text-fg-muted">Entrega un archivo.</p>}
            </li>
          );
        })}
      </ol>
      {(design.connections ?? []).length > 0 && (
        <p className="text-[13px] text-fg">
          Usa: <span className="font-medium text-fg-strong">{design.connections.map(systemName).join(", ")}</span>.
        </p>
      )}
      {(design.writes ?? []).length > 0 && (
        <fieldset className="flex flex-col gap-1.5 rounded-lg border border-line px-4 py-3">
          <legend className="px-1 text-xs font-medium text-fg-muted">Acciones que los agentes podrán hacer por su cuenta</legend>
          {design.writes.map((w) => (
            <label key={w} className="flex cursor-pointer items-center gap-2 text-[13px] text-fg">
              <input type="checkbox" checked={writes.includes(w)} onChange={(e) => setWrites((all) => (e.target.checked ? [...all, w] : all.filter((x) => x !== w)))} />
              <span className="font-mono text-xs">{w.split(":")[1]}</span> <span className="text-fg-muted">en {systemName(w.split(":")[0])}</span>
            </label>
          ))}
          <span className="text-xs text-fg-faint">Si la desmarcas, el agente deja el borrador y la hace una persona. Se puede cambiar después en Conexiones.</span>
        </fieldset>
      )}
      {(design.missing ?? []).length > 0 && (
        <div className="rounded-lg border border-warn/40 bg-warn/10 px-4 py-3 text-[13px] text-fg">
          <p className="font-medium text-fg-strong">Falta conectar</p>
          <ul className="mt-1 list-disc pl-5">
            {design.missing.map((m) => (
              <li key={m}>{m}</li>
            ))}
          </ul>
          <p className="mt-1 text-xs text-fg-muted">
            Puedes conectarlo ahora en{" "}
            <a href="/connections" target="_blank" rel="noreferrer" className="k-link">
              Conexiones
            </a>{" "}
            (se abre aparte; vuelve aquí y pulsa «Ajustar la propuesta» con «ya conecté…») o crear el tablero igual y conectarlo después.
          </p>
        </div>
      )}
      {card && (
        <p className="text-[13px] text-fg">
          Cada tarjeta es un <span className="font-medium text-fg-strong">{card.label.toLowerCase()}</span>
          {(card.fields ?? []).length > 0 && <> con {(card.fields ?? []).map((f) => `${f.label.toLowerCase()} (${FIELD_KIND_LABEL[f.kind].toLowerCase()})`).join(", ")}</>}.
        </p>
      )}
      <div className="flex flex-col gap-2 rounded-lg border border-line px-4 py-3">
        <Field label="¿Quieres cambiar algo?" hint="Por ejemplo: «agrega una aprobación si el monto supera 5.000» o «el cruce lo hace el Analista».">
          {(id) => <TextArea id={id} rows={2} maxLength={1500} value={change} onChange={(e) => setChange(e.target.value)} />}
        </Field>
        <div className="flex justify-end">
          <Button size="sm" icon={Sparkles} loading={pending} disabled={!change.trim()} onClick={refine}>
            Ajustar la propuesta
          </Button>
        </div>
      </div>
      <div className="grid grid-cols-[7rem_minmax(0,1fr)] gap-3">
        <Field label="Clave">
          {(id) => (
            <TextInput id={id} required maxLength={10} pattern="[A-Za-z][A-Za-z0-9]{1,9}" title="2 a 10 letras o números, empezando por letra" value={key} onChange={(e) => setKey(e.target.value.toUpperCase())} className="font-mono uppercase" />
          )}
        </Field>
        <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />}</Field>
      </div>
      {error && (
        <p role="alert" className="text-[13px] text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end gap-2">
        <Button tone="ghost" disabled={pending} onClick={() => { setDesign(null); setError(null); }}>
          {questions ? "Volver a las preguntas" : "Cambiar la descripción"}
        </Button>
        <Button type="submit" tone="primary" loading={pending}>
          Crear tablero
        </Button>
      </div>
    </form>
  );
}
