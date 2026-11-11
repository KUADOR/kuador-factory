"use client";

import { ExternalLink } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { proposeAgentKitAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Badge, Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { KitFileState, RepoKit } from "@/lib/types";

const STATE: Record<KitFileState, { label: string; tone: "ok" | "warn" | "neutral" }> = {
  CURRENT: { label: "Al día", tone: "ok" },
  OUTDATED: { label: "Desactualizado", tone: "warn" },
  MISSING: { label: "Falta", tone: "warn" },
};

/**
 * Kit de agentes de cada repo del proyecto (servidor MCP, /pendientes, subagentes, Codex). Cuando cambian los roles,
 * las reglas o los idiomas, el tablero lo propone por PR: nunca escribe directo en la rama.
 */
export function AgentKitPanel({ projectKey, repos, error }: { projectKey: string; repos: RepoKit[]; error: string | null }) {
  const router = useRouter();
  const [current, setCurrent] = useState(repos);
  const [pending, start] = useTransition();
  // Con un PR abierto, el botón suma los cambios nuevos a ese mismo PR.
  const outdated = current.filter((r) => r.files.some((f) => f.state !== "CURRENT"));

  const propose = () =>
    start(async () => {
      const r = await proposeAgentKitAction(projectKey);
      if (r.ok) {
        setCurrent(r.data);
        notify.ok("Kit propuesto", "Revisa y fusiona el PR en GitHub: al fusionarlo, el repo queda al día.");
        router.refresh();
      } else notify.error("No se pudo proponer el kit", r.error);
    });

  return (
    <Section
      title="Kit de agentes"
      description="Lo que cada persona necesita para trabajar con sus agentes en este repo: la conexión al tablero, el comando /pendientes, un subagente por rol y la configuración de Codex. Sin secretos."
      actions={
        outdated.length > 0 ? (
          <Button tone="primary" size="sm" loading={pending} onClick={propose}>
            {outdated.every((r) => r.pullRequest) ? "Actualizar el PR" : "Proponer actualización"}
          </Button>
        ) : undefined
      }
      flush
    >
      {error ? (
        <p className="px-5 py-4 text-[13px] text-fg-muted">{error}</p>
      ) : (
        <ul className="divide-y divide-line">
          {current.map((repo) => {
            const pendingFiles = repo.files.filter((f) => f.state !== "CURRENT");
            return (
              <li key={repo.repository} className="flex flex-col gap-2 px-5 py-3.5">
                <div className="flex flex-wrap items-center gap-2">
                  <code className="font-mono text-xs text-fg-strong">{repo.repository}</code>
                  <Badge tone={pendingFiles.length === 0 ? "ok" : "warn"}>
                    {pendingFiles.length === 0 ? "Al día" : `${pendingFiles.length} archivo${pendingFiles.length === 1 ? "" : "s"} por actualizar`}
                  </Badge>
                  {repo.pullRequest && (
                    <a href={repo.pullRequest.url} target="_blank" rel="noreferrer" className="k-link inline-flex items-center gap-1 text-xs">
                      PR #{repo.pullRequest.number} abierto <ExternalLink className="size-3" aria-hidden />
                    </a>
                  )}
                </div>
                {pendingFiles.length > 0 && (
                  <ul className="flex flex-col gap-1">
                    {pendingFiles.map((f) => (
                      <li key={f.path} className="flex items-center gap-2 text-xs">
                        <code className="font-mono text-fg-muted">{f.path}</code>
                        <Badge tone={STATE[f.state].tone}>{STATE[f.state].label}</Badge>
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </Section>
  );
}
