import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireEditor } from "@/lib/require-editor";

import { AgentSetup } from "./AgentSetup";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Agentes locales" };

/** Servidores MCP que el kit trae marcados la primera vez. */
const DEFAULT_MCP = ["CONTEXT7"];

export default async function LocalAgentsPage() {
  await requireEditor();
  let data;
  try {
    data = await Promise.all([boardApi.runnerTokens(), boardApi.catalog("mcp-servers"), boardApi.agentKit("claude-code", DEFAULT_MCP), boardApi.me()]);
  } catch (e) {
    return failPage(e);
  }
  const [tokens, servers, kit, me] = data;
  return (
    <>
      <PageHeader
        title="Agentes locales"
        width="narrow"
        description="Los agentes del equipo trabajan en el cliente de IA de cada persona (Claude Code, Codex u otro con MCP), con su propia cuenta, y solo atienden los issues que esa persona supervisa."
      />
      <AgentSetup
        tokens={tokens}
        servers={servers}
        initialKit={kit}
        initialMcp={DEFAULT_MCP}
        boardUrl={process.env.BOARD_PUBLIC_API_URL ?? process.env.BOARD_API_URL ?? "http://localhost:8091"}
        personName={me.name}
      />
    </>
  );
}
