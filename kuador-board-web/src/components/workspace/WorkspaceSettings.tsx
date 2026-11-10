"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { updateWorkspaceAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { notify } from "@/components/ui/Toaster";
import type { Workspace } from "@/lib/types";

import { BrandFields, type BrandDraft } from "./BrandFields";
import { BrandPreview } from "./BrandPreview";

/** Marca del espacio: los cambios se ven al instante en la vista previa y en todo el tablero al guardar. */
export function WorkspaceSettings({ workspace }: { workspace: Workspace }) {
  const router = useRouter();
  const initial: BrandDraft = { name: workspace.name, description: workspace.description ?? "", brandColor: workspace.brandColor, brandOnColor: workspace.brandOnColor, logo: workspace.logo };
  const [draft, setDraft] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const dirty = JSON.stringify(draft) !== JSON.stringify(initial);

  return (
    <form
      className="grid gap-8 xl:grid-cols-[minmax(0,36rem)_minmax(0,1fr)]"
      onSubmit={(e) => {
        e.preventDefault();
        start(async () => {
          const r = await updateWorkspaceAction({ name: draft.name, description: draft.description.trim() || null, brandColor: draft.brandColor.toLowerCase(), brandOnColor: draft.brandOnColor, logo: draft.logo });
          if (r.ok) {
            setError(null);
            notify.ok("Espacio actualizado");
            router.refresh();
          } else setError(r.error);
        });
      }}
    >
      <div className="flex flex-col gap-5 rounded-lg border border-line bg-surface p-5">
        <BrandFields value={draft} onChange={setDraft} nameLabel="Nombre del espacio" />
        <p className="text-xs text-fg-faint">
          Identificador: <span className="font-mono text-fg-muted">{workspace.slug}</span> (no se cambia).
        </p>
        {error && <p role="alert" className="text-[13px] text-danger">{error}</p>}
        <div className="flex justify-end gap-2 border-t border-line pt-4">
          <Button tone="ghost" disabled={!dirty || pending} onClick={() => setDraft(initial)}>Descartar</Button>
          <Button type="submit" tone="primary" loading={pending} disabled={!dirty}>Guardar cambios</Button>
        </div>
      </div>
      <div className="flex flex-col gap-3">
        <span className="text-xs font-medium text-fg-muted">Vista previa</span>
        <BrandPreview value={draft} />
      </div>
    </form>
  );
}
