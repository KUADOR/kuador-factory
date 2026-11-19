"use client";

import { Plus } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { savePlatformPackageAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ChipPicker } from "@/components/ui/ChipPicker";
import { Field, SelectInput, Switch, TextArea, TextInput } from "@/components/ui/Field";
import { Badge, Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { CatalogPackage } from "@/lib/types";

type Template = { key: string; name: string };

const BLANK: CatalogPackage = { code: "", name: "", description: "", templates: [], starterTemplate: null, starterKey: null, starterName: null, sortOrder: 100, active: true };

/** Paquetes por industria: la empresa elige uno al registrarse; arranca con su tablero y se le recomiendan sus plantillas. */
export function PackagesEditor({ packages, templates }: { packages: CatalogPackage[]; templates: Template[] }) {
  const [adding, setAdding] = useState(false);
  return (
    <Section
      title="Paquetes por industria"
      actions={!adding && <Button icon={Plus} onClick={() => setAdding(true)}>Nuevo paquete</Button>}
      flush
    >
      <ul className="divide-y divide-line">
        {adding && (
          <li>
            <PackageForm initial={BLANK} templates={templates} isNew onDone={() => setAdding(false)} />
          </li>
        )}
        {packages.map((p) => (
          <li key={p.code}>
            <PackageForm initial={p} templates={templates} />
          </li>
        ))}
      </ul>
    </Section>
  );
}

function PackageForm({ initial, templates, isNew = false, onDone }: { initial: CatalogPackage; templates: Template[]; isNew?: boolean; onDone?: () => void }) {
  const router = useRouter();
  const [open, setOpen] = useState(isNew);
  const [p, setP] = useState(initial);
  const [pending, start] = useTransition();
  const name = (key: string) => templates.find((t) => t.key === key)?.name ?? key;

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3 text-left hover:bg-hover">
        <span className="w-24 font-mono text-xs text-fg-muted">{initial.code}</span>
        <span className="text-sm font-medium text-fg-strong">{initial.name}</span>
        <span className="min-w-0 flex-1 truncate text-xs text-fg-muted">
          {initial.templates.map(name).join(" · ")}
          {initial.starterTemplate ? ` · arranca con «${initial.starterName}»` : ""}
        </span>
        {!initial.active && <Badge>Inactivo</Badge>}
      </button>
    );
  }

  return (
    <form
      className="flex flex-col gap-4 bg-sunken/40 px-5 py-4"
      onSubmit={(e) => {
        e.preventDefault();
        start(async () => {
          const r = await savePlatformPackageAction({ ...p, code: p.code.trim().toUpperCase(), starterKey: p.starterKey?.trim().toUpperCase() || null });
          if (!r.ok) {
            notify.error("No se pudo guardar", r.error);
            return;
          }
          notify.ok(`Paquete ${r.data.name} guardado`);
          setOpen(false);
          onDone?.();
          router.refresh();
        });
      }}
    >
      <div className="grid gap-4 sm:grid-cols-[10rem_minmax(0,1fr)]">
        <Field label="Código" hint={isNew ? "Mayúsculas, ej. COMMERCE." : undefined}>
          {(id) => <TextInput id={id} required disabled={!isNew} value={p.code} onChange={(e) => setP({ ...p, code: e.target.value.toUpperCase() })} className="font-mono" />}
        </Field>
        <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={60} value={p.name} onChange={(e) => setP({ ...p, name: e.target.value })} />}</Field>
      </div>
      <Field label="Para quién es" hint="La lee la empresa al registrarse.">
        {(id) => <TextArea id={id} rows={2} maxLength={300} value={p.description} onChange={(e) => setP({ ...p, description: e.target.value })} />}
      </Field>
      <div className="flex flex-col gap-1.5">
        <span className="text-xs font-medium text-fg-muted">Plantillas recomendadas (en este orden)</span>
        <ChipPicker
          value={p.templates}
          options={templates.map((t) => ({ value: t.key, label: t.name }))}
          onChange={(v) => setP({ ...p, templates: v, starterTemplate: p.starterTemplate && v.includes(p.starterTemplate) ? p.starterTemplate : null })}
          placeholder="Agregar plantilla…"
          ariaLabel="Plantillas del paquete"
          empty="ninguna"
        />
      </div>
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="Tablero inicial">
          {(id) => (
            <SelectInput
              id={id}
              value={p.starterTemplate ?? "NONE"}
              onChange={(v) => setP({ ...p, starterTemplate: !v || v === "NONE" ? null : v })}
              options={[{ value: "NONE", label: "Ninguno" }, ...p.templates.map((t) => ({ value: t, label: name(t) }))]}
            />
          )}
        </Field>
        <Field label="Clave del tablero" hint="Mayúsculas, ej. VEN.">
          {(id) => <TextInput id={id} disabled={!p.starterTemplate} maxLength={10} value={p.starterKey ?? ""} onChange={(e) => setP({ ...p, starterKey: e.target.value.toUpperCase() })} className="font-mono" />}
        </Field>
        <Field label="Nombre del tablero">
          {(id) => <TextInput id={id} disabled={!p.starterTemplate} maxLength={120} value={p.starterName ?? ""} onChange={(e) => setP({ ...p, starterName: e.target.value })} />}
        </Field>
      </div>
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
        <Switch checked={p.active} onChange={(v) => setP({ ...p, active: v })} label="Se ofrece al registrarse" />
        <label className="flex items-center gap-2 text-sm text-fg">
          Orden
          <TextInput type="number" value={p.sortOrder} onChange={(e) => setP({ ...p, sortOrder: Math.floor(Number(e.target.value)) })} className="w-20" />
        </label>
      </div>
      <div className="flex justify-end gap-2">
        <Button tone="ghost" onClick={() => { setP(initial); setOpen(false); onDone?.(); }}>Cancelar</Button>
        <Button type="submit" tone="primary" loading={pending}>Guardar paquete</Button>
      </div>
    </form>
  );
}
