"use client";

import Link from "next/link";
import { useState, useTransition } from "react";

import { confirmPasswordResetAction, requestPasswordResetAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, PasswordInput, TextInput } from "@/components/ui/Field";

/**
 * Recuperar la contraseña: con el enlace del correo se elige la nueva directamente; sin él, se pide el correo
 * y se escribe el código de 6 dígitos que llega junto con el enlace.
 */
export function ResetFlow({ token }: { token: string | null }) {
  const [step, setStep] = useState<"email" | "code" | "done">(token ? "code" : "email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  const send = () =>
    start(async () => {
      const r = await requestPasswordResetAction(email);
      if (!r.ok) return setError(r.error);
      setError(null);
      setStep("code");
    });

  return (
    <div className="rounded-lg border border-line bg-surface p-6 shadow-float">
      <h1 className="text-lg font-semibold tracking-tight text-fg-strong">{step === "done" ? "Contraseña actualizada" : "Recuperar contraseña"}</h1>

      {step === "email" && (
        <form className="mt-6 flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); send(); }}>
          <Field label="Correo">
            {(id) => <TextInput id={id} type="email" required autoFocus autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />}
          </Field>
          <Button type="submit" tone="primary" loading={pending} className="w-full">Enviar enlace y código</Button>
        </form>
      )}

      {step === "code" && (
        <form
          className="mt-6 flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (password !== confirm) return setError("Las contraseñas no coinciden.");
            start(async () => {
              const r = await confirmPasswordResetAction(
                token ? { token, email: null, code: null, password } : { token: null, email, code, password },
              );
              if (!r.ok) return setError(r.error);
              setError(null);
              setStep("done");
            });
          }}
        >
          {!token && (
            <>
              <p className="-mt-4 text-[13px] text-fg-muted">
                Si {email} tiene cuenta, le llegó un enlace y un código. Revisa también el spam.
              </p>
              <Field label="Código">
                {(id) => (
                  <TextInput
                    id={id}
                    required
                    autoFocus
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    pattern="\d{6}"
                    maxLength={6}
                    value={code}
                    onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                    className="tabular text-center font-mono text-xl tracking-[0.5em]"
                  />
                )}
              </Field>
            </>
          )}
          <Field label="Contraseña nueva" hint="Mínimo 10 caracteres.">
            {(id) => <PasswordInput id={id} required autoFocus={!!token} minLength={10} maxLength={128} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />}
          </Field>
          <Field label="Repite la contraseña">
            {(id) => <PasswordInput id={id} required minLength={10} maxLength={128} autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />}
          </Field>
          <Button type="submit" tone="primary" loading={pending} disabled={!token && code.length !== 6} className="w-full">Guardar contraseña</Button>
          {!token && (
            <button type="button" className="k-link self-start text-xs" disabled={pending} onClick={() => { setStep("email"); setCode(""); }}>
              Usar otro correo o pedir otro código
            </button>
          )}
        </form>
      )}

      {step === "done" && (
        <div className="mt-2 flex flex-col gap-4">
          <p className="text-[13px] text-fg-muted">Cerramos las sesiones abiertas. Entra con tu contraseña nueva.</p>
          <Button href="/login" tone="primary" className="w-full">Entrar</Button>
        </div>
      )}

      {error && <p role="alert" className="mt-4 text-[13px] text-danger">{error}</p>}
      {step !== "done" && (
        <Link href="/login" className="k-link mt-4 inline-block text-xs">
          Volver a entrar
        </Link>
      )}
    </div>
  );
}
