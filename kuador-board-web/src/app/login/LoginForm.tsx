"use client";

import { useState, useTransition } from "react";

import { loginAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextInput, PasswordInput } from "@/components/ui/Field";

export function LoginForm({ next }: { next: string }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault();
        startTransition(async () => {
          const result = await loginAction(email, password, next);
          if (!result.ok) setError(result.error);
        });
      }}
    >
      <Field label="Correo">{(id) => <TextInput id={id} type="email" autoComplete="username" required autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />}</Field>
      <Field label="Contraseña">{(id) => <PasswordInput id={id} autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>
      {error && (
        <p role="alert" className="rounded-md bg-danger/10 px-3 py-2 text-[13px] text-danger">
          {error}
        </p>
      )}
      <Button type="submit" tone="primary" loading={pending} className="w-full">
        Entrar
      </Button>
    </form>
  );
}
