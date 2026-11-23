"use client";

import { useState, useTransition } from "react";

import { saveMailConnectionAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, PasswordInput, Switch, TextInput } from "@/components/ui/Field";
import type { BusinessConnection, MailDetection } from "@/lib/types";

/** «facturas@empresa.com» → CORREO_FACTURAS (la clave con la que los agentes ven sus herramientas). */
function keyFor(email: string) {
  const user = email.split("@")[0] ?? "";
  return `CORREO_${user.normalize("NFD").replace(/[̀-ͯ]/g, "").toUpperCase().replace(/[^A-Z0-9]+/g, "_")}`.replace(/_+$/g, "").slice(0, 24);
}

/**
 * Un buzón que se conecta con su correo y contraseña (Hostinger, cPanel, Zoho…). Los servidores vienen de la detección
 * por el dominio; si no se conocen, quedan a la vista para revisarlos.
 */
export function MailboxForm({ detected, onDone, onCancel }: { detected: MailDetection; onDone: (c: BusinessConnection) => void; onCancel: () => void }) {
  const [form, setForm] = useState({
    name: `Correo ${detected.email}`.slice(0, 80),
    email: detected.email,
    password: "",
    imapHost: detected.imapHost ?? "",
    imapPort: String(detected.imapPort ?? 993),
    smtpHost: detected.smtpHost ?? "",
    smtpPort: String(detected.smtpPort ?? 465),
    smtpSsl: detected.smtpSsl ?? true,
  });
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));

  const submit = () =>
    start(async () => {
      const r = await saveMailConnectionAction(keyFor(form.email), {
        name: form.name.trim(),
        email: form.email.trim(),
        password: form.password,
        imapHost: form.imapHost.trim(),
        imapPort: Number(form.imapPort),
        smtpHost: form.smtpHost.trim(),
        smtpPort: Number(form.smtpPort),
        smtpSsl: form.smtpSsl,
      });
      if (!r.ok) return setError(r.error);
      onDone(r.data);
    });

  return (
    <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); submit(); }}>
      <p className="text-[13px] text-fg-muted">
        {detected.provider}. Se conecta con el correo y su contraseña; conviene un buzón dedicado (por ejemplo, facturas@) y no el personal de alguien.
      </p>
      <Field label="Nombre">
        {(id) => <TextInput id={id} required maxLength={80} value={form.name} onChange={(e) => set({ name: e.target.value })} />}
      </Field>
      <Field label="Correo">
        {(id) => <TextInput id={id} required type="email" maxLength={254} autoComplete="off" value={form.email} onChange={(e) => set({ email: e.target.value })} />}
      </Field>
      <Field label="Contraseña del correo" hint="Se guarda cifrada y no se vuelve a mostrar." error={error}>
        {(id) => <PasswordInput id={id} required autoComplete="new-password" value={form.password} onChange={(e) => set({ password: e.target.value })} />}
      </Field>
      <details className="text-[13px] text-fg-muted" open={!detected.sure}>
        <summary className="cursor-pointer select-none">Servidores{detected.sure ? "" : " (revísalos: los indica tu proveedor de correo)"}</summary>
        <div className="mt-3 grid grid-cols-[1fr_6rem] gap-3">
          <Field label="Servidor de entrada (IMAP)">
            {(id) => <TextInput id={id} required maxLength={253} value={form.imapHost} onChange={(e) => set({ imapHost: e.target.value })} placeholder="imap.empresa.com" />}
          </Field>
          <Field label="Puerto">
            {(id) => <TextInput id={id} required inputMode="numeric" value={form.imapPort} onChange={(e) => set({ imapPort: e.target.value.replace(/\D/g, "") })} />}
          </Field>
          <Field label="Servidor de salida (SMTP)">
            {(id) => <TextInput id={id} required maxLength={253} value={form.smtpHost} onChange={(e) => set({ smtpHost: e.target.value })} placeholder="smtp.empresa.com" />}
          </Field>
          <Field label="Puerto">
            {(id) => (
              <TextInput
                id={id}
                required
                inputMode="numeric"
                value={form.smtpPort}
                onChange={(e) => {
                  const port = e.target.value.replace(/\D/g, "");
                  set({ smtpPort: port, smtpSsl: port === "465" ? true : port === "587" ? false : form.smtpSsl });
                }}
              />
            )}
          </Field>
        </div>
        <div className="mt-3">
          <Switch checked={form.smtpSsl} label="Salida con SSL (puerto 465); apagado usa STARTTLS (587)" onChange={(on) => set({ smtpSsl: on })} />
        </div>
      </details>
      <div className="flex justify-end gap-2">
        <Button tone="ghost" onClick={onCancel}>Cancelar</Button>
        <Button type="submit" tone="primary" loading={pending} disabled={!form.email.trim() || !form.password || !form.imapHost.trim() || !form.smtpHost.trim()}>
          Conectar
        </Button>
      </div>
    </form>
  );
}
