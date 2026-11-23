"use client";

import { useState, useTransition } from "react";

import { setCapabilitiesAction } from "@/app/actions";
import { Switch } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import { CAPABILITY_LABEL } from "@/lib/labels";
import type { Capability, Project } from "@/lib/types";

/** Lo que usa el tablero además del flujo de tarjetas. El repositorio de código se elige al crear el tablero. */
export function CapabilitiesForm({ project }: { project: Project }) {
  const [capabilities, setCapabilities] = useState<Capability[]>(project.capabilities);
  const [pending, start] = useTransition();
  const toggle = (capability: Capability, on: boolean) => {
    const next = on ? [...capabilities, capability] : capabilities.filter((c) => c !== capability);
    start(async () => {
      const r = await setCapabilitiesAction(project.key, next);
      if (r.ok) setCapabilities(r.data.capabilities);
      else notify.error("No se pudo cambiar", r.error);
    });
  };
  const editable: Capability[] = capabilities.includes("REPOSITORY") ? ["SPLIT"] : ["DOCUMENTS", "SPLIT"];
  return (
    <div className="flex flex-col divide-y divide-line rounded-lg border border-line bg-surface">
      {editable.map((c) => (
        <div key={c} className="flex items-center justify-between gap-4 px-5 py-3.5">
          <div className="min-w-0">
            <p className="text-[13px] font-medium text-fg-strong">{CAPABILITY_LABEL[c].label}</p>
            <p className="mt-0.5 text-xs text-fg-muted">{CAPABILITY_LABEL[c].hint}</p>
          </div>
          <Switch checked={capabilities.includes(c)} disabled={pending} onChange={(on) => toggle(c, on)} label={CAPABILITY_LABEL[c].label} hideLabel />
        </div>
      ))}
    </div>
  );
}
