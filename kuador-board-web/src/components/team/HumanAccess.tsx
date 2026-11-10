"use client";

import { useState, useTransition } from "react";

import { grantAccessAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextInput, PasswordInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";

/** Acceso web de una persona: correo y contraseña temporal (se comparte por un canal seguro). */
export function HumanAccess({ memberId, email }: { memberId: string; email: string | null }) {
  const [open, setOpen] = useState(false);
  const [newEmail, setNewEmail] = useState(email ?? "");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2 text-[13px]">
        <span className="min-w-0 flex-1 truncate">{email ? <span className="text-fg">{email}</span> : <span className="text-fg-faint">Sin acceso al tablero</span>}</span>
        {!open && (
          <Button size="sm" onClick={() => setOpen(true)}>
            {email ? "Restablecer contraseña" : "Dar acceso"}
          </Button>
        )}
      </div>
      {open && (
        <form
          className="flex flex-col gap-3 rounded-md border border-line bg-sunken p-3"
          onSubmit={(e) => {
            e.preventDefault();
            startTransition(async () => {
              const r = await grantAccessAction(memberId, newEmail, password);
              if (r.ok) {
                setPassword("");
                setOpen(false);
                setError(null);
                notify.ok("Acceso guardado", "Comparte la contraseña temporal por un canal seguro.");
              } else setError(r.error);
            });
          }}
        >
          <Field label="Correo">{(id) => <TextInput id={id} size="sm" type="email" required value={newEmail} onChange={(e) => setNewEmail(e.target.value)} placeholder="correo@kuador.com" />}</Field>
          <Field label="Contraseña temporal" hint="Mínimo 10 caracteres. Cierra sus otras sesiones." error={error}>
            {(id) => (
              <PasswordInput id={id} size="sm" required minLength={10} maxLength={128} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            )}
          </Field>
          <div className="flex justify-end gap-2">
            <Button size="sm" tone="ghost" onClick={() => setOpen(false)}>
              Cancelar
            </Button>
            <Button size="sm" tone="primary" type="submit" loading={pending}>
              Guardar acceso
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}
