"use client";

import { useState, useTransition } from "react";

import { oauthDecisionAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import type { OAuthClientInfo } from "@/lib/types";

type Request = { clientId: string; redirectUri: string | null; codeChallenge: string | null; codeChallengeMethod: string | null; state: string | null };

/** Quién pide, qué podrá hacer y a dónde vuelve. Aprobar crea una conexión a nombre de la persona (revocable). */
export function ConsentForm({
  client,
  personName,
  workspaceName,
  viewOnly,
  request,
}: {
  client: OAuthClientInfo;
  personName: string;
  workspaceName: string;
  viewOnly: boolean;
  request: Request;
}) {
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const decide = (approved: boolean) =>
    start(async () => {
      const r = await oauthDecisionAction({ ...request, approved });
      if (r && !r.ok) setError(r.error);
    });

  return (
    <div className="w-full max-w-md rounded-lg border border-line bg-surface p-6 shadow-float">
      <h1 className="text-lg font-semibold tracking-tight text-fg-strong">Conectar «{client.name}»</h1>
      <p className="mt-2 text-[13px] leading-relaxed text-fg-muted">
        Este cliente de IA quiere entrar al tablero de <span className="font-medium text-fg-strong">{workspaceName}</span> como <span className="font-medium text-fg-strong">{personName}</span>.
      </p>
      <ul className="mt-4 flex list-disc flex-col gap-1.5 pl-5 text-[13px] leading-relaxed text-fg">
        <li>Verá tus pendientes y trabajará como los agentes del equipo solo los issues que tú supervisas.</li>
        <li>No puede entrar a la web, aprobar puertas ni administrar el espacio.</li>
        <li>Puedes desconectarlo cuando quieras desde Agentes locales.</li>
      </ul>
      <p className="mt-4 rounded-md border border-line bg-sunken px-3 py-2 text-xs text-fg-muted">
        {client.local ? (
          <>Vuelve a un programa de este equipo (<span className="font-mono">{client.redirectHost}</span>). Si no acabas de iniciar sesión desde tu cliente de IA, cancela.</>
        ) : (
          <>
            Vuelve a <span className="font-mono text-fg-strong">{client.redirectHost}</span>. Aprueba solo si reconoces ese destino.
          </>
        )}
      </p>
      {viewOnly && <p className="mt-4 text-[13px] text-danger">Tu acceso es de solo lectura: no puedes poner agentes a trabajar.</p>}
      {error && (
        <p role="alert" className="mt-4 text-[13px] text-danger">
          {error}
        </p>
      )}
      <div className="mt-6 flex justify-end gap-2">
        <Button tone="ghost" disabled={pending} onClick={() => decide(false)}>
          Cancelar
        </Button>
        <Button tone="primary" loading={pending} disabled={viewOnly} onClick={() => decide(true)}>
          Permitir
        </Button>
      </div>
    </div>
  );
}
