"use client";

import { Tooltip as PrimeTooltip } from "@primereact/ui/tooltip";
import { cloneElement, type ReactElement } from "react";

/** Ayuda breve al pasar el mouse o enfocar. No sustituye a una etiqueta visible en controles. */
export function Tooltip({ content, children }: { content: React.ReactNode; children: React.ReactElement }) {
  return (
    <PrimeTooltip.Root>
      <PrimeTooltip.Trigger asChild>{(_: unknown, attrs: Record<string, unknown>) => cloneElement(children as ReactElement<Record<string, unknown>>, attrs)}</PrimeTooltip.Trigger>
      <PrimeTooltip.Portal>
        <PrimeTooltip.Positioner sideOffset={6}>
          <PrimeTooltip.Popup className="max-w-xs text-xs">{content}</PrimeTooltip.Popup>
        </PrimeTooltip.Positioner>
      </PrimeTooltip.Portal>
    </PrimeTooltip.Root>
  );
}

export const TooltipManager = PrimeTooltip.Manager;
