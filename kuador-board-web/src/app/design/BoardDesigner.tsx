"use client";

import { Lock, Sparkles } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { createDesignedBoardAction, designBoardAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextArea, TextInput } from "@/components/ui/Field";
import { FIELD_KIND_LABEL } from "@/lib/labels";
import type { BoardDesign } from "@/lib/types";

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
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  // Las acciones que cambian datos que la persona deja habilitar al crear (vienen todas marcadas: las propuso para el trabajo).
  const [writes, setWrites] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const roleName = (k: string) => roles.find((r) => r.key === k)?.name ?? k;
  const systemName = (k: string) => systems.find((x) => x.key === k)?.name ?? k;

  const propose = () =>
    start(async () => {
      const r = await designBoardAction(description);
      if (!r.ok) return setError(r.error);
      setDesign(r.data);
      setWrites(r.data.writes ?? []);
      setKey(r.data.key);
      setName(r.data.name);
      setError(null);
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

  if (!design) {
    return (
      <form className="flex flex-col gap-4 px-4 py-5 sm:px-6" onSubmit={(e) => { e.preventDefault(); propose(); }}>
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
            Proponer tablero
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
            Puedes crear el tablero igual y conectarlo después en{" "}
            <Link href="/connections" className="k-link">
              Conexiones
            </Link>
            .
          </p>
        </div>
      )}
      {card && (
        <p className="text-[13px] text-fg">
          Cada tarjeta es un <span className="font-medium text-fg-strong">{card.label.toLowerCase()}</span>
          {(card.fields ?? []).length > 0 && <> con {(card.fields ?? []).map((f) => `${f.label.toLowerCase()} (${FIELD_KIND_LABEL[f.kind].toLowerCase()})`).join(", ")}</>}.
        </p>
      )}
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
          Cambiar la descripción
        </Button>
        <Button type="submit" tone="primary" loading={pending}>
          Crear tablero
        </Button>
      </div>
    </form>
  );
}
