import Link from "next/link";
import { Sparkle } from "lucide-react";

import { Badge } from "@/components/ui/Section";

import type { AgentRun, AgentRunStatus } from "@/lib/types";

import { RetryRunButton } from "./RetryRunButton";

const STATUS: Record<AgentRunStatus, { label: string; tone: "sol" | "ok" | "danger" | "neutral" }> = {
  RUNNING: { label: "Trabajando…", tone: "sol" },
  SUCCEEDED: { label: "Listo", tone: "ok" },
  FAILED: { label: "Falló", tone: "danger" },
  SKIPPED: { label: "Omitido", tone: "neutral" },
};

const dateFormat = new Intl.DateTimeFormat("es-EC", { dateStyle: "short", timeStyle: "short", timeZone: "America/Guayaquil" });

/** Una ejecución de un agente: estado, qué hizo (o por qué no) y cuántos tokens usó. */
export function AgentRunItem({ run, agentName, showIssue = false }: { run: AgentRun; agentName?: string; showIssue?: boolean }) {
  const s = STATUS[run.status];
  return (
    <li className="flex flex-col gap-1 rounded-md border border-line bg-surface px-3 py-2 text-xs">
      <div className="flex flex-wrap items-center gap-2">
        <Sparkle className="size-3.5 fill-sol text-sol" aria-hidden />
        {agentName && <span className="font-medium text-fg-strong">{agentName}</span>}
        {showIssue && (
          <Link href={`/projects/${run.projectKey}?issue=${run.issueKey}`} className="k-link font-mono">
            {run.issueKey}
          </Link>
        )}
        <Badge tone={s.tone}>{s.label}</Badge>
        <time dateTime={run.createdAt} className="tabular ml-auto text-[11px] text-fg-faint">
          {dateFormat.format(new Date(run.createdAt))}
        </time>
      </div>
      {run.summary && <p className="text-fg">{run.summary}</p>}
      {run.error && <p className="text-danger">{run.error}</p>}
      <div className="flex justify-end empty:hidden">
        <RetryRunButton run={run} />
      </div>
      {run.model?.startsWith("local:") || run.model === "claude-code" ? (
        <p className="font-mono text-[11px] text-fg-faint">Trabajado en {clientName(run.model)}, en el equipo de quien lo supervisa</p>
      ) : run.inputTokens != null && (
        <p className="tabular font-mono text-[11px] text-fg-faint">
          {run.model} · {run.inputTokens.toLocaleString("es-EC")} tokens de entrada · {(run.outputTokens ?? 0).toLocaleString("es-EC")} de salida
        </p>
      )}
    </li>
  );
}

const CLIENT_NAMES: Record<string, string> = { "claude-code": "Claude Code", codex: "Codex", mcp: "un cliente MCP" };

/** «local:codex» → «Codex»; un cliente desconocido se muestra tal cual. */
function clientName(model: string): string {
  const id = model.replace(/^local:/, "");
  return CLIENT_NAMES[id] ?? id;
}
