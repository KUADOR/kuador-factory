"use client";

import { useState, useTransition } from "react";

import { changePasswordAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, PasswordInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";

export function ChangePasswordForm() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault();
        if (next !== confirm) return setError("Las contraseñas nuevas no coinciden.");
        startTransition(async () => {
          const result = await changePasswordAction(current, next);
          if (result.ok) {
            setCurrent("");
            setNext("");
            setConfirm("");
            setError(null);
            notify.ok("Contraseña actualizada", "Cerramos tus otras sesiones abiertas.");
          } else setError(result.error);
        });
      }}
    >
      <Field label="Contraseña actual">{(id) => <PasswordInput id={id} required autoComplete="current-password" maxLength={128} value={current} onChange={(e) => setCurrent(e.target.value)} />}</Field>
      <Field label="Nueva contraseña" hint="Mínimo 10 caracteres.">
        {(id) => <PasswordInput id={id} required autoComplete="new-password" minLength={10} maxLength={128} value={next} onChange={(e) => setNext(e.target.value)} />}
      </Field>
      <Field label="Repite la nueva contraseña" error={error}>
        {(id) => <PasswordInput id={id} required autoComplete="new-password" minLength={10} maxLength={128} value={confirm} onChange={(e) => setConfirm(e.target.value)} />}
      </Field>
      <Button type="submit" tone="primary" loading={pending} className="self-start">
        Cambiar contraseña
      </Button>
    </form>
  );
}
