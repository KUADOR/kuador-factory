"use client";

import { ExternalLink, GitMerge } from "lucide-react";
import { useEffect, useState, useTransition } from "react";

import { cardPullsAction, mergePullAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { decisionsChanged } from "@/components/shell/useDecisionsCount";
import { notify } from "@/components/ui/Toaster";
import type { CardPull } from "@/lib/types";

const CI: Record<CardPull["ci"], { label: string; tone: string }> = {
  GREEN: { label: "CI en verde", tone: "text-ok" },
  RED: { label: "CI en rojo", tone: "text-danger" },
  PENDING: { label: "CI corriendo", tone: "text-fg-muted" },
  NONE: { label: "sin CI", tone: "text-fg-muted" },
  UNREADABLE: { label: "no se puede leer el CI", tone: "text-warn" },
};

/**
 * Los PRs de la tarjeta en su puerta, con «Aprobar y fusionar» si el CI está en verde: quien aprueba cierra el ciclo sin
 * abrir GitHub (también desde el celular). Si la tarjeta no tiene PRs, no se muestra.
 */
export function PullMerge({ projectKey, issueKey }: { projectKey: string; issueKey: string }) {
  const [pulls, setPulls] = useState<CardPull[] | null>(null);
  const [pending, start] = useTransition();

  const running = (pulls ?? []).some((p) => !p.merged && p.ci === "PENDING");
  useEffect(() => {
    let alive = true;
    const load = () => cardPullsAction(issueKey).then((r) => alive && setPulls(r.ok ? r.data : []));
    load();
    // Mientras el CI corre, se vuelve a mirar: el botón se habilita solo apenas queda en verde.
    const timer = running ? setInterval(load, 20_000) : undefined;
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [issueKey, running]);

  const open = (pulls ?? []).filter((p) => !p.merged && p.state.toLowerCase() === "open");
  if (open.length === 0) return null;

  return (
    <ul className="flex flex-col gap-2">
      {open.map((p) => (
        <li key={`${p.repository}#${p.number}`} className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <div className="min-w-0 flex-1">
            <p className="truncate text-[13px] text-fg-strong" title={p.title}>
              {p.repository} #{p.number} · {p.title}
            </p>
            <p className={`text-xs ${CI[p.ci].tone}`}>{CI[p.ci].label}</p>
            {p.ci !== "GREEN" && (p.note || p.ci === "PENDING" || p.ci === "RED") && (
              <p className="mt-0.5 text-xs leading-relaxed text-fg-muted">
                {p.note ??
                  (p.ci === "PENDING"
                    ? "Se habilita solo cuando el CI termine en verde."
                    : "Corrige el CI antes de fusionar.")}
              </p>
            )}
          </div>
          <Button size="sm" tone="ghost" icon={ExternalLink} href={p.url} label="Ver en GitHub" iconOnly />
          <Button
            size="sm"
            tone="primary"
            icon={GitMerge}
            loading={pending}
            disabled={p.ci !== "GREEN"}
            onClick={() =>
              start(async () => {
                const r = await mergePullAction(projectKey, issueKey, p.repository, p.number);
                if (!r.ok) {
                  notify.error("No se pudo fusionar", r.error);
                  return;
                }
                notify.ok(`Fusionado ${p.repository} #${p.number}`, `${issueKey} avanzó.`);
                decisionsChanged();
                setPulls((all) => (all ?? []).map((x) => (x === p ? { ...x, merged: true } : x)));
              })
            }
          >
            Aprobar y fusionar
          </Button>
        </li>
      ))}
    </ul>
  );
}
