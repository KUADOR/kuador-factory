"use client";

import { Check, ChevronDown } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";
import { clsx } from "clsx";

import { BottomSheet } from "@/components/ui/BottomSheet";

export interface PickerItem {
  href: string;
  label: string;
  /** Solo activo en esa ruta exacta (no en sus hijas). */
  exact?: boolean;
}

export const isActive = (path: string, item: PickerItem) => path === item.href || (!item.exact && path.startsWith(item.href + "/"));

/**
 * En el celular, las secciones de una zona en vez de una fila de pestañas que no cabe: un botón con la sección
 * actual que abre la lista completa desde abajo.
 */
export function SectionPicker({
  label,
  groups,
  className,
  compact,
}: {
  label: string;
  groups: { title?: string; items: PickerItem[] }[];
  className?: string;
  /** Sin la etiqueta, para ir al lado de un título. */
  compact?: boolean;
}) {
  const path = usePathname();
  // La hoja recuerda en qué ruta se abrió: al navegar queda cerrada sola.
  const [openAt, setOpenAt] = useState<string | null>(null);
  const items = groups.flatMap((g) => g.items);
  // La más específica gana (p. ej. «Configuración» frente a «Tablero» en la raíz del proyecto).
  const current = items.filter((i) => isActive(path, i)).sort((a, b) => b.href.length - a.href.length)[0];
  return (
    <>
      <button
        type="button"
        onClick={() => setOpenAt(path)}
        aria-haspopup="dialog"
        aria-label={compact ? `${label}: ${current?.label ?? ""}` : undefined}
        className={clsx(
          "flex items-center gap-2 rounded-lg border border-line bg-surface text-left text-fg-strong active:bg-hover",
          compact ? "h-9 max-w-full px-2.5 text-[13px]" : "h-10 w-full px-3 text-[14px]",
          className,
        )}
      >
        {!compact && <span className="text-xs text-fg-faint">{label}</span>}
        <span className="min-w-0 flex-1 truncate font-medium">{current?.label ?? "Elegir"}</span>
        <ChevronDown className="size-4 shrink-0 text-fg-muted" aria-hidden />
      </button>
      <BottomSheet open={openAt === path} onClose={() => setOpenAt(null)} title={label}>
        {groups.map((g, i) => (
          <div key={g.title ?? i} className={clsx(i > 0 && "mt-2 border-t border-line pt-2")}>
            {g.title && <p className="px-3 pt-1 pb-1 text-[11px] font-medium tracking-wide text-fg-faint uppercase">{g.title}</p>}
            <ul>
              {g.items.map((item) => {
                const on = item === current;
                return (
                  <li key={item.href}>
                    <Link
                      href={item.href}
                      aria-current={on ? "page" : undefined}
                      onClick={() => on && setOpenAt(null)}
                      className={clsx("flex h-12 items-center gap-3 rounded-lg px-3 text-[15px]", on ? "bg-selected font-medium text-fg-strong" : "text-fg active:bg-hover")}
                    >
                      <span className="flex-1">{item.label}</span>
                      {on && <Check className="size-4 text-sol-text" aria-hidden />}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </BottomSheet>
    </>
  );
}
