import type { Metadata } from "next";

import { ApiError, boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import type { Me, OAuthClientInfo, Workspace } from "@/lib/types";

import { ConsentForm } from "./ConsentForm";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Conectar cliente de IA" };

const one = (v: string | string[] | undefined) => (typeof v === "string" && v.length > 0 ? v : null);

/**
 * Pantalla de permiso del inicio de sesión OAuth del servidor MCP. Sin sesión, el proxy lleva al login y vuelve
 * aquí. Si el cliente o su destino no son válidos, se explica aquí: nunca se redirige a un destino sin validar.
 */
export default async function AuthorizePage({ searchParams }: PageProps<"/oauth/authorize">) {
  const params = await searchParams;
  const clientId = one(params.client_id);
  const redirectUri = one(params.redirect_uri);
  if (!clientId || one(params.response_type) !== "code") {
    return <Problem message="El enlace de conexión está incompleto. Vuelve a iniciar sesión desde tu cliente de IA." />;
  }
  let client: OAuthClientInfo, me: Me, workspace: Workspace;
  try {
    [client, me, workspace] = await Promise.all([boardApi.oauthClient(clientId, redirectUri), boardApi.me(), boardApi.workspace()]);
  } catch (e) {
    if (e instanceof ApiError && e.status === 400) return <Problem message={e.message} />;
    if (e instanceof ApiError && e.status === 401) return <Problem message="Ese cliente ya no está registrado. Vuelve a iniciar sesión desde tu cliente de IA." />;
    return failPage(e);
  }
  return (
    <main className="grid place-items-center px-4 py-16">
      <ConsentForm
        client={client}
        personName={me.name}
        workspaceName={workspace.name}
        viewOnly={me.access === "VIEW"}
        request={{
          clientId,
          redirectUri,
          codeChallenge: one(params.code_challenge),
          codeChallengeMethod: one(params.code_challenge_method),
          state: one(params.state),
        }}
      />
    </main>
  );
}

function Problem({ message }: { message: string }) {
  return (
    <main className="grid place-items-center px-4 py-16">
      <div className="w-full max-w-md rounded-lg border border-line bg-surface p-6">
        <h1 className="text-base font-semibold text-fg-strong">No se pudo conectar</h1>
        <p className="mt-2 text-[13px] leading-relaxed text-fg-muted">{message}</p>
      </div>
    </main>
  );
}
