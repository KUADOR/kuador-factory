import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireEditor } from "@/lib/require-editor";

import { ClaudeCodeSetup } from "./ClaudeCodeSetup";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Claude Code" };

/** Servidores MCP que el kit trae marcados la primera vez. */
const DEFAULT_MCP = ["CONTEXT7"];

export default async function ClaudeCodePage() {
  await requireEditor();
  let data;
  try {
    data = await Promise.all([boardApi.runnerTokens(), boardApi.catalog("mcp-servers"), boardApi.claudeKit(DEFAULT_MCP), boardApi.me()]);
  } catch (e) {
    return failPage(e);
  }
  const [tokens, servers, files, me] = data;
  return (
    <>
      <PageHeader
        title="Claude Code"
        width="narrow"
        description="Tus agentes trabajan en tu Claude Code, con tu suscripción, y solo atienden los issues que tú supervisas."
      />
      <ClaudeCodeSetup
        tokens={tokens}
        servers={servers}
        initialFiles={files}
        initialMcp={DEFAULT_MCP}
        boardUrl={process.env.BOARD_PUBLIC_API_URL ?? process.env.BOARD_API_URL ?? "http://localhost:8091"}
        personName={me.name}
      />
    </>
  );
}
