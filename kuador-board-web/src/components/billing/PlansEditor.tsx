"use client";

import { Plus } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { savePlanAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, Switch, TextArea, TextInput } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { Plan } from "@/lib/types";

import { priceLabel } from "./format";

const BLANK: Plan = {
  code: "",
  name: "",
  tagline: "",
  priceMonthly: 0,
  currency: "USD",
  features: [],
  maxMembers: null,
  maxProjects: null,
  factory: false,
  trialDays: 0,
  highlighted: false,
  sortOrder: 100,
  active: true,
};

/** Planes a la venta: lo que se edita aquí es lo que muestran la presentación y el alta. */
export function PlansEditor({ plans }: { plans: Plan[] }) {
  const [adding, setAdding] = useState(false);
  return (
    <Section
      title="Planes"
      description="Se muestran en la presentación y al crear un espacio, en este orden. Un plan inactivo deja de venderse; los espacios que ya lo tienen lo conservan."
      actions={!adding && <Button icon={Plus} onClick={() => setAdding(true)}>Nuevo plan</Button>}
      flush
    >
      <ul className="divide-y divide-line">
        {adding && (
          <li>
            <PlanForm initial={BLANK} isNew onDone={() => setAdding(false)} />
          </li>
        )}
        {plans.map((p) => (
          <li key={p.code}>
            <PlanForm initial={p} />
          </li>
        ))}
      </ul>
    </Section>
  );
}

function PlanForm({ initial, isNew = false, onDone }: { initial: Plan; isNew?: boolean; onDone?: () => void }) {
  const router = useRouter();
  const [open, setOpen] = useState(isNew);
  const [p, setP] = useState(initial);
  const [features, setFeatures] = useState(initial.features.join("\n"));
  const [pending, start] = useTransition();
  const set = <K extends keyof Plan>(key: K, value: Plan[K]) => setP((prev) => ({ ...prev, [key]: value }));
  const limit = (raw: string) => (raw.trim() === "" ? null : Math.max(1, Math.floor(Number(raw))));

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3 text-left hover:bg-hover">
        <span className="w-24 font-mono text-xs text-fg-muted">{initial.code}</span>
        <span className="text-sm font-medium text-fg-strong">{initial.name}</span>
        <span className="tabular text-sm text-fg">{priceLabel(initial)}</span>
        <span className="text-xs text-fg-muted">
          {initial.maxMembers ?? "∞"} personas · {initial.maxProjects ?? "∞"} tableros{initial.factory ? " · fábrica" : ""}
          {initial.trialDays > 0 ? ` · ${initial.trialDays} días de prueba` : ""}
        </span>
        {!initial.active && <span className="rounded bg-hover px-1.5 text-[11px] text-fg-muted">Inactivo</span>}
      </button>
    );
  }

  return (
    <form
      className="flex flex-col gap-4 bg-sunken/40 px-5 py-4"
      onSubmit={(e) => {
        e.preventDefault();
        start(async () => {
          const { code, ...input } = { ...p, code: p.code.trim().toUpperCase(), features: features.split("\n").map((f) => f.trim()).filter(Boolean) };
          const r = await savePlanAction(code, input);
          if (!r.ok) {
            notify.error(r.error);
            return;
          }
          notify.ok(`Plan ${r.data.name} guardado`);
          setOpen(false);
          onDone?.();
          router.refresh();
        });
      }}
    >
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Field label="Código" hint={isNew ? "Mayúsculas, ej. PRO. No se cambia después." : undefined}>
          {(id) => <TextInput id={id} required disabled={!isNew} pattern="[A-Za-z][A-Za-z0-9_]{1,29}" value={p.code} onChange={(e) => set("code", e.target.value.toUpperCase())} className="font-mono" />}
        </Field>
        <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={60} value={p.name} onChange={(e) => set("name", e.target.value)} />}</Field>
        <Field label="Precio al mes">
          {(id) => <TextInput id={id} required type="number" min={0} step="0.01" value={p.priceMonthly} onChange={(e) => set("priceMonthly", Number(e.target.value))} />}
        </Field>
        <Field label="Moneda">{(id) => <TextInput id={id} required maxLength={3} value={p.currency} onChange={(e) => set("currency", e.target.value.toUpperCase())} />}</Field>
      </div>
      <Field label="Frase" hint="Para quién es el plan, en una línea.">
        {(id) => <TextInput id={id} required maxLength={200} value={p.tagline} onChange={(e) => set("tagline", e.target.value)} />}
      </Field>
      <Field label="Qué incluye" hint="Una línea por característica (hasta 12). Los límites de personas y tableros se muestran solos.">
        {(id) => <TextArea id={id} rows={4} value={features} onChange={(e) => setFeatures(e.target.value)} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Field label="Máx. personas" hint="Vacío = sin límite.">
          {(id) => <TextInput id={id} type="number" min={1} value={p.maxMembers ?? ""} onChange={(e) => set("maxMembers", limit(e.target.value))} />}
        </Field>
        <Field label="Máx. tableros" hint="Vacío = sin límite.">
          {(id) => <TextInput id={id} type="number" min={1} value={p.maxProjects ?? ""} onChange={(e) => set("maxProjects", limit(e.target.value))} />}
        </Field>
        <Field label="Días de prueba" hint="0 = sin prueba.">
          {(id) => <TextInput id={id} type="number" min={0} max={365} value={p.trialDays} onChange={(e) => set("trialDays", Math.max(0, Math.floor(Number(e.target.value))))} />}
        </Field>
        <Field label="Orden">{(id) => <TextInput id={id} type="number" value={p.sortOrder} onChange={(e) => set("sortOrder", Math.floor(Number(e.target.value)))} />}</Field>
      </div>
      <div className="flex flex-wrap gap-x-6 gap-y-3">
        <Switch checked={p.factory} onChange={(v) => set("factory", v)} label="Incluye la fábrica de software" />
        <Switch checked={p.highlighted} onChange={(v) => set("highlighted", v)} label="Recomendado" />
        <Switch checked={p.active} onChange={(v) => set("active", v)} label="A la venta" />
      </div>
      <div className="flex justify-end gap-2">
        <Button
          tone="ghost"
          onClick={() => {
            setP(initial);
            setFeatures(initial.features.join("\n"));
            setOpen(false);
            onDone?.();
          }}
        >
          Cancelar
        </Button>
        <Button type="submit" tone="primary" loading={pending}>Guardar plan</Button>
      </div>
    </form>
  );
}
