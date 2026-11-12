"use client";

import { Send } from "lucide-react";
import { useRouter } from "next/navigation";
import { useTransition } from "react";

import { publishPlatformCatalogAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { notify } from "@/components/ui/Toaster";
import type { PlatformCatalogSummary } from "@/lib/types";

/** Versión publicada, cambios pendientes y el botón para publicar. */
export function PublishCatalog({ summary }: { summary: PlatformCatalogSummary }) {
  const router = useRouter();
  const [pending, start] = useTransition();
  return (
    <div className="flex items-center gap-3">
      <span className="text-xs text-fg-muted">
        Versión {summary.version}
        {summary.pending > 0 ? ` · ${summary.pending} cambio${summary.pending === 1 ? "" : "s"} sin publicar` : " · todo publicado"}
      </span>
      <Button
        tone="primary"
        size="sm"
        icon={Send}
        loading={pending}
        disabled={summary.pending === 0}
        onClick={() =>
          start(async () => {
            const r = await publishPlatformCatalogAction();
            if (!r.ok) {
              notify.error("No se pudo publicar", r.error);
              return;
            }
            notify.ok(`Versión ${r.data.version} publicada`, "Los espacios la reciben en unos segundos; se respeta lo que cada equipo modificó.");
            router.refresh();
          })
        }
      >
        Publicar versión
      </Button>
    </div>
  );
}
