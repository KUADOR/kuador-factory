"use client";

import { createAgentTokenAction, revokeAgentTokenAction } from "@/app/actions";
import { TokenManager } from "@/components/tokens/TokenManager";
import type { AgentToken } from "@/lib/types";

/** Tokens de servicio del agente (Authorization: Bearer). El secreto se ve una sola vez. */
export function AgentTokens({ memberId, tokens }: { memberId: string; tokens: AgentToken[] }) {
  return (
    <TokenManager
      tokens={tokens}
      create={(name) => createAgentTokenAction(memberId, name)}
      revoke={revokeAgentTokenAction}
      copy={{
        empty: "Sin tokens.",
        namePlaceholder: "Nombre del token, ej. integración CI",
        create: "Crear token",
        revokeTitle: (name) => `¿Revocar «${name}»?`,
        revokeMessage: "El agente dejará de tener acceso con este token de inmediato.",
        revoked: "Token revocado",
      }}
    />
  );
}
