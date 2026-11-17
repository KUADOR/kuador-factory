"use client";

import { ExternalLink, GitMerge } from "lucide-react";
import { useEffect, useState, useTransition } from "react";

import { cardPullsAction, mergePullAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { notify } from "@/components/ui/Toaster";
import type { CardPull } from "@/lib/types";

const CI: Record<CardPull["ci"], { label: string; tone: string }> = {
  GREEN: { label: "CI en verde", tone: "text-ok" },
  RED: { label: "CI en rojo", tone: "text-danger" },
  PENDING: { label: "CI corriendo", tone: "text-fg-muted" },
  NONE: { label: "sin CI", tone: "text-fg-muted" },
};

/**
 * Los PRs de la tarjeta en su puerta, con «Aprobar y fusionar» si el CI está en verde: quien aprueba cierra el ciclo sin
 * abrir GitHub (también desde el celular). Si la tarjeta no tiene PRs, no se muestra.
 */
export function PullMerge({ projectKey, issueKey }: { projectKey: string; issueKey: string }) {
  const [pulls, setPulls] = useState<CardPull[] | null>(null);
  const [pending, start] = useTransition();

  useEffect(() => {
    let alive = true;
    cardPullsAction(issueKey).then((r) => alive && setPulls(r.ok ? r.data : []));
    return () => {
      alive = false;
    };
  }, [issueKey]);

  const open = (pulls ?? []).filter((p) => !p.merged && p.state.toLowerCase() === "open");
  if (open.length === 0) return null;

  return (
    <ul className="flex flex-col gap-2 rounded-md border border-line bg-sunken px-3 py-2.5">
      {open.map((p) => (
        <li key={`${p.repository}#${p.number}`} className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <div className="min-w-0 flex-1">
            <p className="truncate text-[13px] text-fg-strong" title={p.title}>
              {p.repository} #{p.number} · {p.title}
            </p>
            <p className={`text-xs ${CI[p.ci].tone}`}>{CI[p.ci].label}</p>
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
