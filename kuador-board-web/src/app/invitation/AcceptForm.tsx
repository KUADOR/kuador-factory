"use client";

import { useState, useTransition } from "react";

import { acceptInvitationAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, PasswordInput } from "@/components/ui/Field";

export function AcceptForm({ token }: { token: string }) {
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault();
        if (password !== confirm) return setError("Las contraseñas no coinciden.");
        start(async () => {
          const r = await acceptInvitationAction(token, password);
          if (r && !r.ok) setError(r.error);
        });
      }}
    >
      <Field label="Contraseña" hint="Mínimo 10 caracteres.">
        {(id) => <PasswordInput id={id} required autoFocus minLength={10} maxLength={128} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />}
      </Field>
      <Field label="Repite la contraseña">
        {(id) => <PasswordInput id={id} required minLength={10} maxLength={128} autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />}
      </Field>
      <Button type="submit" tone="primary" loading={pending} className="w-full">Aceptar y entrar</Button>
      {error && <p role="alert" className="rounded-md bg-danger/10 px-3 py-2 text-[13px] text-danger">{error}</p>}
    </form>
  );
}
