"use client";

import { Copy } from "lucide-react";
import { useState, useTransition } from "react";

import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { ActionResult, AgentToken, IssuedToken } from "@/lib/types";

const dateFormat = new Intl.DateTimeFormat("es-EC", { dateStyle: "medium", timeStyle: "short" });

export interface TokenCopy {
  empty: string;
  namePlaceholder: string;
  create: string;
  revokeTitle: (name: string) => string;
  revokeMessage: string;
  revoked: string;
}

/**
 * Lista de tokens con alta y revocación. El secreto se muestra una sola vez, al crearlo; {@code onIssued}
 * permite mostrar algo más con él (p. ej. el comando para usarlo).
 */
export function TokenManager({
  tokens,
  copy,
  create,
  revoke,
  onIssued,
}: {
  tokens: Pick<AgentToken, "id" | "name" | "lastUsedAt">[];
  copy: TokenCopy;
  create: (name: string) => Promise<ActionResult<IssuedToken>>;
  revoke: (id: string) => Promise<ActionResult<void>>;
  onIssued?: (secret: string) => React.ReactNode;
}) {
  const [name, setName] = useState("");
  const [secret, setSecret] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<Pick<AgentToken, "id" | "name"> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  return (
    <div className="flex flex-col gap-3">
      {tokens.length === 0 ? (
        <p className="text-[13px] text-fg-faint">{copy.empty}</p>
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
          {onIssued?.(secret)}
        </div>
      )}
      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          startTransition(async () => {
            const r = await create(name);
            if (r.ok) {
              setSecret(r.data.token);
              setName("");
              setError(null);
            } else setError(r.error);
          });
        }}
      >
        <TextInput size="sm" required maxLength={80} value={name} onChange={(e) => setName(e.target.value)} placeholder={copy.namePlaceholder} aria-label="Nombre" />
        <Button size="sm" type="submit" loading={pending} className="shrink-0">
          {copy.create}
        </Button>
      </form>
      {error && <p className="text-xs text-danger">{error}</p>}
      <ConfirmDialog
        open={revoking != null}
        onCancel={() => setRevoking(null)}
        title={copy.revokeTitle(revoking?.name ?? "")}
        message={copy.revokeMessage}
        confirmLabel="Revocar"
        pending={pending}
        onConfirm={() =>
          startTransition(async () => {
            const r = revoking ? await revoke(revoking.id) : null;
            setRevoking(null);
            if (r && !r.ok) setError(r.error);
            else notify.ok(copy.revoked);
          })
        }
      />
    </div>
  );
}
