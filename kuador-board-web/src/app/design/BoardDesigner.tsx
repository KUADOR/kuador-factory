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
export function BoardDesigner({ roles, aiReady }: { roles: { key: string; name: string }[]; aiReady: boolean }) {
  const router = useRouter();
  const [description, setDescription] = useState("");
  const [design, setDesign] = useState<BoardDesign | null>(null);
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const roleName = (k: string) => roles.find((r) => r.key === k)?.name ?? k;

  const propose = () =>
    start(async () => {
      const r = await designBoardAction(description);
      if (!r.ok) return setError(r.error);
      setDesign(r.data);
      setKey(r.data.key);
      setName(r.data.name);
      setError(null);
    });

  const create = () =>
    start(async () => {
      if (!design) return;
      const r = await createDesignedBoardAction(design, key, name);
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
              {(stage?.checklist ?? []).length > 0 && <p className="pl-7 text-xs text-fg-muted">Comprueba: {(stage?.checklist ?? []).join(" · ")}</p>}
              {(stage?.requires ?? []).some((r) => r.kind === "ATTACHMENT") && <p className="pl-7 text-xs text-fg-muted">Entrega un archivo.</p>}
            </li>
          );
        })}
      </ol>
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
