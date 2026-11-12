"use client";

import { Copy, Plus } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { savePaymentProviderAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, PasswordInput, SelectInput, Switch, TextInput } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { PaymentMode, PaymentProviderView, PaymentType } from "@/lib/types";

/**
 * Pasarelas de pago. Cada una usa un adaptador del servidor (su tipo) y el formulario muestra las credenciales que
 * ese adaptador pide: sumar un proveedor nuevo no cambia esta pantalla.
 */
export function PaymentProvidersEditor({ providers, types }: { providers: PaymentProviderView[]; types: PaymentType[] }) {
  const [adding, setAdding] = useState(false);
  const blank: PaymentProviderView | null = types[0]
    ? { code: types[0].type, type: types[0].type, name: types[0].label, mode: "SANDBOX", active: false, sortOrder: 10, values: {}, secretsSet: [], webhookUrl: "" }
    : null;
  return (
    <Section
      title="Pasarelas de pago"
      description="Con qué se cobran los planes. Prueba en modo sandbox y cambia a real cuando esté lista; solo las activas aparecen al pagar."
      actions={!adding && blank && <Button icon={Plus} onClick={() => setAdding(true)}>Nueva pasarela</Button>}
      flush
    >
      <ul className="divide-y divide-line">
        {adding && blank && (
          <li>
            <ProviderForm initial={blank} types={types} isNew onDone={() => setAdding(false)} />
          </li>
        )}
        {providers.map((p) => (
          <li key={p.code}>
            <ProviderForm initial={p} types={types} />
          </li>
        ))}
        {providers.length === 0 && !adding && <li className="px-5 py-4 text-[13px] text-fg-faint">Ninguna pasarela todavía: los planes se activan a mano.</li>}
      </ul>
    </Section>
  );
}

function ProviderForm({ initial, types, isNew = false, onDone }: { initial: PaymentProviderView; types: PaymentType[]; isNew?: boolean; onDone?: () => void }) {
  const router = useRouter();
  const [open, setOpen] = useState(isNew);
  const [p, setP] = useState(initial);
  const [credentials, setCredentials] = useState<Record<string, string>>(initial.values);
  const [pending, start] = useTransition();
  const type = types.find((t) => t.type === p.type);

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3 text-left hover:bg-hover">
        <span className="w-24 font-mono text-xs text-fg-muted">{initial.code}</span>
        <span className="text-sm font-medium text-fg-strong">{initial.name}</span>
        <span className="text-xs text-fg-muted">{initial.mode === "LIVE" ? "Real" : "Sandbox"}</span>
        <span className={initial.active ? "rounded bg-ok/10 px-1.5 text-[11px] text-ok" : "rounded bg-hover px-1.5 text-[11px] text-fg-muted"}>
          {initial.active ? "Activa" : "Inactiva"}
        </span>
      </button>
    );
  }

  return (
    <form
      className="flex flex-col gap-4 bg-sunken/40 px-5 py-4"
      onSubmit={(e) => {
        e.preventDefault();
        start(async () => {
          const code = p.code.trim().toUpperCase();
          const r = await savePaymentProviderAction(code, { type: p.type, name: p.name, mode: p.mode, credentials, active: p.active, sortOrder: p.sortOrder });
          if (!r.ok) {
            notify.error("No se pudo guardar", r.error);
            return;
          }
          notify.ok(`Pasarela ${r.data.name} guardada`);
          setOpen(false);
          onDone?.();
          router.refresh();
        });
      }}
    >
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Field label="Código" hint={isNew ? "Mayúsculas, ej. PAYPAL. No se cambia después." : undefined}>
          {(id) => <TextInput id={id} required disabled={!isNew} value={p.code} onChange={(e) => setP({ ...p, code: e.target.value.toUpperCase() })} className="font-mono" />}
        </Field>
        <Field label="Tipo">
          {(id) => (
            <SelectInput
              id={id}
              disabled={!isNew}
              value={p.type}
              onChange={(v) => v && setP({ ...p, type: v })}
              options={types.map((t) => ({ value: t.type, label: t.label }))}
            />
          )}
        </Field>
        <Field label="Nombre que ve el cliente">{(id) => <TextInput id={id} required maxLength={60} value={p.name} onChange={(e) => setP({ ...p, name: e.target.value })} />}</Field>
        <Field label="Modo">
          {(id) => (
            <SelectInput<PaymentMode>
              id={id}
              value={p.mode}
              onChange={(v) => v && setP({ ...p, mode: v })}
              options={[
                { value: "SANDBOX", label: "Sandbox (pruebas)" },
                { value: "LIVE", label: "Real" },
              ]}
            />
          )}
        </Field>
      </div>
      {type?.fields.map((f) => (
        <Field key={f.key} label={f.label + (f.required ? "" : " (opcional)")} hint={f.secret && initial.secretsSet.includes(f.key) ? "Guardado. Déjalo vacío para conservarlo." : f.hint ?? undefined}>
          {(id) =>
            f.secret ? (
              <PasswordInput id={id} autoComplete="off" value={credentials[f.key] ?? ""} onChange={(e) => setCredentials({ ...credentials, [f.key]: e.target.value })} />
            ) : (
              <TextInput id={id} value={credentials[f.key] ?? ""} onChange={(e) => setCredentials({ ...credentials, [f.key]: e.target.value })} />
            )
          }
        </Field>
      ))}
      {initial.webhookUrl && (
        <div className="flex flex-col gap-1.5 text-[13px]">
          <span className="text-xs font-medium text-fg-muted">URL de avisos (webhook) para registrar en la pasarela</span>
          <div className="flex items-center gap-2">
            <code className="min-w-0 flex-1 truncate rounded bg-surface px-2 py-1.5 font-mono text-xs text-fg-strong">{initial.webhookUrl}</code>
            <Button size="sm" icon={Copy} iconOnly label="Copiar" onClick={() => void navigator.clipboard.writeText(initial.webhookUrl).then(() => notify.ok("Copiado"))} />
          </div>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
        <Switch checked={p.active} onChange={(v) => setP({ ...p, active: v })} label="Activa (aparece al pagar)" />
        <label className="flex items-center gap-2 text-sm text-fg">
          Orden
          <TextInput type="number" value={p.sortOrder} onChange={(e) => setP({ ...p, sortOrder: Math.floor(Number(e.target.value)) })} className="w-20" />
        </label>
      </div>
      <div className="flex justify-end gap-2">
        <Button
          tone="ghost"
          onClick={() => {
            setP(initial);
            setCredentials(initial.values);
            setOpen(false);
            onDone?.();
          }}
        >
          Cancelar
        </Button>
        <Button type="submit" tone="primary" loading={pending}>Guardar pasarela</Button>
      </div>
    </form>
  );
}
