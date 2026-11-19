"use client";

import { RotateCcw } from "lucide-react";
import { useState, useTransition } from "react";

import { retryAgentRunAction } from "@/app/actions";
import { useAccess } from "@/components/shell/AccessContext";
import { Button } from "@/components/ui/Button";
import { notify } from "@/components/ui/Toaster";
import type { AgentRun } from "@/lib/types";

/**
 * Vuelve a lanzar un trabajo que falló o se saltó, p. ej. después de acortar los comentarios o de poner la clave de IA.
 * Solo para quien edita y trabajos del servidor (los de la PC de una persona se reintentan con /pendientes).
 */
export function RetryRunButton({ run }: { run: AgentRun }) {
  const { canEdit } = useAccess();
  const [sent, setSent] = useState(false);
  const [pending, start] = useTransition();
  const local = run.model?.startsWith("local:") || run.model === "claude-code";
  if (!canEdit || local || (run.status !== "FAILED" && run.status !== "SKIPPED")) return null;
  return (
    <Button
      size="sm"
      tone="ghost"
      icon={RotateCcw}
      loading={pending}
      disabled={sent}
      onClick={() =>
        start(async () => {
          const r = await retryAgentRunAction(run.id, run.projectKey);
          if (r.ok) {
            setSent(true);
            notify.ok("Reintentando");
          } else notify.error("No se pudo reintentar", r.error);
        })
      }
    >
      {sent ? "Reintentando…" : "Reintentar"}
    </Button>
  );
}
