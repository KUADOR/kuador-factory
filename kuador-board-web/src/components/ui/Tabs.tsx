"use client";

import { Tabs as PrimeTabs } from "@primereact/ui/tabs";
import type { LucideIcon } from "lucide-react";

export interface TabItem<V extends string> {
  value: V;
  label: string;
  icon?: LucideIcon;
  /** Número opcional junto a la etiqueta (comentarios, documentos…). */
  count?: number;
  content: React.ReactNode;
}

/** Pestañas dentro de una vista (no navegación entre páginas: para eso, ProjectTabs con enlaces). */
export function Tabs<V extends string>({ items, value, onChange, ariaLabel }: { items: TabItem<V>[]; value: V; onChange: (value: V) => void; ariaLabel: string }) {
  return (
    <PrimeTabs.Root value={value} onValueChange={(e: { value?: string | number }) => e.value != null && onChange(e.value as V)}>
      <PrimeTabs.List aria-label={ariaLabel}>
        <PrimeTabs.Content>
          {items.map((t) => (
            <PrimeTabs.Tab key={t.value} value={t.value} className="flex-row items-center gap-1.5 px-3 py-2 text-[13px] font-medium text-fg-muted data-[active]:text-fg-strong">
              {t.icon && <t.icon className="size-3.5" aria-hidden />}
              {t.label}
              {t.count != null && t.count > 0 && <span className="tabular font-mono text-[11px] text-fg-faint">{t.count}</span>}
            </PrimeTabs.Tab>
          ))}
          <PrimeTabs.Indicator />
        </PrimeTabs.Content>
      </PrimeTabs.List>
      <PrimeTabs.Panels className="px-0 pt-4">
        {items.map((t) => (
          <PrimeTabs.Panel key={t.value} value={t.value}>
            {value === t.value ? t.content : null}
          </PrimeTabs.Panel>
        ))}
      </PrimeTabs.Panels>
    </PrimeTabs.Root>
  );
}
