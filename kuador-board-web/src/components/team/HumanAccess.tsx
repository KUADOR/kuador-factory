"use client";

import { useState, useTransition } from "react";

import { cancelInvitationAction, grantAccessAction, inviteAction, resendInvitationAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextInput, PasswordInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { Invitation } from "@/lib/types";

const date = (iso: string) => new Intl.DateTimeFormat("es-EC", { day: "numeric", month: "short" }).format(new Date(iso));

/**
 * Acceso web de una persona. Lo habitual es invitarla por correo (elige su contraseña); como alternativa, se le
 * pone una contraseña temporal que se comparte por un canal seguro.
 */
export function HumanAccess({ memberId, email, invitation }: { memberId: string; email: string | null; invitation: Invitation | null }) {
  const [mode, setMode] = useState<"idle" | "invite" | "password">("idle");
  const [newEmail, setNewEmail] = useState(email ?? invitation?.email ?? "");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  const act = (work: () => Promise<{ ok: boolean; error?: string }>, done: string) =>
    startTransition(async () => {
      const r = await work();
      if (!r.ok) {
        setError(r.error ?? null);
        return;
      }
      setError(null);
      setMode("idle");
      setPassword("");
      notify.ok(done);
    });

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2 text-[13px]">
        <span className="min-w-0 flex-1 truncate">
          {email ? (
            <span className="text-fg">{email}</span>
          ) : invitation ? (
            <span className="text-fg">
              Invitación a {invitation.email} · {invitation.expired ? "venció" : `enviada el ${date(invitation.createdAt)}`}
            </span>
          ) : (
            <span className="text-fg-faint">Sin acceso al tablero</span>
          )}
        </span>
      </div>

      {mode === "idle" && (
        <div className="flex flex-wrap gap-2">
          {email ? (
            <p className="text-xs text-fg-muted">Si olvida su contraseña, la recupera con «¿Olvidaste tu contraseña?» al iniciar sesión.</p>
          ) : invitation ? (
            <>
              <Button size="sm" tone="primary" loading={pending} onClick={() => act(() => resendInvitationAction(invitation.id), "Invitación reenviada")}>
                Reenviar invitación
              </Button>
              <Button size="sm" tone="ghost" disabled={pending} onClick={() => act(() => cancelInvitationAction(invitation.id), "Invitación anulada")}>
                Anular
              </Button>
            </>
          ) : (
            <>
              <Button size="sm" tone="primary" onClick={() => setMode("invite")}>Invitar por correo</Button>
              <Button size="sm" tone="ghost" onClick={() => setMode("password")}>Poner contraseña temporal</Button>
            </>
          )}
        </div>
      )}

      {mode === "invite" && (
        <form
          className="flex flex-col gap-3 rounded-md border border-line bg-sunken p-3"
          onSubmit={(e) => {
            e.preventDefault();
            act(() => inviteAction({ memberId, email: newEmail }), "Invitación enviada");
          }}
        >
          <Field label="Correo" hint="Le llega un enlace para elegir su contraseña. Vence en 7 días." error={error}>
            {(id) => <TextInput id={id} size="sm" type="email" required autoFocus value={newEmail} onChange={(e) => setNewEmail(e.target.value)} placeholder="persona@empresa.com" />}
          </Field>
          <div className="flex justify-end gap-2">
            <Button size="sm" tone="ghost" onClick={() => setMode("idle")}>Cancelar</Button>
            <Button size="sm" tone="primary" type="submit" loading={pending}>Enviar invitación</Button>
          </div>
        </form>
      )}

      {mode === "password" && (
        <form
          className="flex flex-col gap-3 rounded-md border border-line bg-sunken p-3"
          onSubmit={(e) => {
            e.preventDefault();
            act(() => grantAccessAction(memberId, newEmail, password), "Acceso guardado. Comparte la contraseña temporal por un canal seguro.");
          }}
        >
          <Field label="Correo">{(id) => <TextInput id={id} size="sm" type="email" required value={newEmail} onChange={(e) => setNewEmail(e.target.value)} placeholder="persona@empresa.com" />}</Field>
          <Field label="Contraseña temporal" hint="Mínimo 10 caracteres. Cierra sus otras sesiones." error={error}>
            {(id) => (
              <PasswordInput id={id} size="sm" required minLength={10} maxLength={128} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            )}
          </Field>
          <div className="flex justify-end gap-2">
            <Button size="sm" tone="ghost" onClick={() => setMode("idle")}>Cancelar</Button>
            <Button size="sm" tone="primary" type="submit" loading={pending}>Guardar acceso</Button>
          </div>
        </form>
      )}
      {error && mode === "idle" && <p role="alert" className="text-[13px] text-danger">{error}</p>}
    </div>
  );
}
