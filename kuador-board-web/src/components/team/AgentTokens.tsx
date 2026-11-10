"use client";

import { Copy } from "lucide-react";
import { useState, useTransition } from "react";

import { createAgentTokenAction, revokeAgentTokenAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { AgentToken } from "@/lib/types";

const dateFormat = new Intl.DateTimeFormat("es-EC", { dateStyle: "medium", timeStyle: "short" });

/** Tokens de servicio del agente (Authorization: Bearer). El secreto se ve una sola vez. */
export function AgentTokens({ memberId, tokens }: { memberId: string; tokens: AgentToken[] }) {
  const [name, setName] = useState("");
  const [secret, setSecret] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<AgentToken | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  return (
    <div className="flex flex-col gap-3">
      {tokens.length === 0 ? (
        <p className="text-[13px] text-fg-faint">Sin tokens: este agente aún no puede usar la API.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
          {tokens.map((t) => (
            <li key={t.id} className="flex items-center gap-2 px-3 py-2 text-[13px]">
              <span className="min-w-0 flex-1">
                <span className="text-fg-strong">{t.name}</span>
                <span className="block text-xs text-fg-faint">{t.lastUsedAt ? `Usado ${dateFormat.format(new Date(t.lastUsedAt))}` : "Sin uso"}</span>
              </span>
              <Button size="sm" tone="ghost" onClick={() => setRevoking(t)}>
                Revocar
              </Button>
            </li>
          ))}
        </ul>
      )}
      {secret && (
        <div role="status" className="rounded-md border border-sol/40 bg-sol/8 p-3 text-xs">
          <p className="mb-1.5 font-medium text-fg-strong">Cópialo ahora: no se volverá a mostrar.</p>
          <div className="flex items-center gap-2">
            <code className="min-w-0 flex-1 truncate font-mono text-[11px] text-fg-strong">{secret}</code>
            <Button
              size="sm"
              tone="ghost"
              icon={Copy}
              iconOnly
              label="Copiar token"
              onClick={async () => {
                await navigator.clipboard.writeText(secret);
                notify.ok("Token copiado");
              }}
            />
          </div>
        </div>
      )}
      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          startTransition(async () => {
            const r = await createAgentTokenAction(memberId, name);
            if (r.ok) {
              setSecret(r.data.token);
              setName("");
              setError(null);
            } else setError(r.error);
          });
        }}
      >
        <TextInput size="sm" required maxLength={80} value={name} onChange={(e) => setName(e.target.value)} placeholder="Nombre del token, ej. runner local" aria-label="Nombre del token" />
        <Button size="sm" type="submit" loading={pending} className="shrink-0">
          Crear token
        </Button>
      </form>
      {error && <p className="text-xs text-danger">{error}</p>}
      <ConfirmDialog
        open={revoking != null}
        onCancel={() => setRevoking(null)}
        title={`¿Revocar «${revoking?.name}»?`}
        message="El agente dejará de tener acceso con este token de inmediato."
        confirmLabel="Revocar token"
        pending={pending}
        onConfirm={() =>
          startTransition(async () => {
            const r = revoking ? await revokeAgentTokenAction(revoking.id) : null;
            setRevoking(null);
            if (r && !r.ok) setError(r.error);
            else notify.ok("Token revocado");
          })
        }
      />
    </div>
  );
}
