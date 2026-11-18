import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireEditor } from "@/lib/require-editor";

import { AgentSetup } from "./AgentSetup";
import { RunnerSection } from "./RunnerSection";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Agentes locales" };

/** Servidores MCP que el kit trae marcados la primera vez. */
const DEFAULT_MCP = ["CONTEXT7"];

export default async function LocalAgentsPage() {
  await requireEditor();
  let data;
  try {
    data = await Promise.all([
      boardApi.runnerTokens(),
      boardApi.catalog("mcp-servers"),
      boardApi.agentKit("claude-code", DEFAULT_MCP),
      boardApi.me(),
      boardApi.runnerMachines().catch(() => []),
      boardApi.catalog("roles").catch(() => []),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [tokens, servers, kit, me, machines, roles] = data;
  // El runner habla con la API por el mismo dominio que el servidor MCP.
  const server = kit.connection.url.replace(/\/mcp$/, "");
  return (
    <>
      <PageHeader
        title="Agentes locales"
        width="narrow"
        description="Conecta el Claude Code o Codex de cada persona con el tablero, y déjalos trabajando solos con el runner."
      />
      <div className="max-w-3xl px-4 pt-5 sm:px-6 sm:pt-6">
        <RunnerSection machines={machines} server={server} roleName={Object.fromEntries(roles.map((r) => [r.key, r.name]))} meId={me.id} />
      </div>
      <AgentSetup
        tokens={tokens}
        servers={servers}
        initialKit={kit}
        initialMcp={DEFAULT_MCP}
        personName={me.name}
      />
    </>
  );
}
