"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { clsx } from "clsx";

export interface SectionNavGroup {
  title?: string;
  items: { href: string; label: string; /** Solo activo en esa ruta exacta (no en sus hijas). */ exact?: boolean }[];
}

/**
 * Secciones de una zona (Configuración, configuración del tablero, Plataforma) como una fila de pestañas sobre el
 * contenido: un solo sidebar en toda la app. Los grupos quedan separados por una línea fina; en el teléfono la fila
 * se desplaza.
 */
export function SectionNav({ label, groups }: { label: string; groups: SectionNavGroup[] }) {
  const path = usePathname();
  const active = (href: string, exact?: boolean) => path === href || (!exact && path.startsWith(href + "/"));
  return (
    <nav aria-label={label} className="-mx-1 flex items-center gap-1 overflow-x-auto px-1 pb-0.5">
      {groups.map((g, i) => (
        <div key={g.title ?? i} role="group" aria-label={g.title} className="flex shrink-0 items-center gap-1">
          {i > 0 && <span aria-hidden className="mx-1.5 h-4 w-px shrink-0 bg-line-strong" />}
          {g.items.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              aria-current={active(item.href, item.exact) ? "page" : undefined}
              className={clsx(
                "rounded-md px-2.5 py-1.5 text-[13px] whitespace-nowrap",
                active(item.href, item.exact) ? "bg-selected font-medium text-fg-strong shadow-[0_0_0_1px_var(--k-line)]" : "text-fg-muted hover:bg-hover hover:text-fg-strong",
              )}
            >
              {item.label}
            </Link>
          ))}
        </div>
      ))}
    </nav>
  );
}

/** Zona con secciones: el título y las pestañas arriba, la página (con su propia cabecera) debajo. */
export function SectionLayout({ title, nav, children }: { title: string; nav: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="flex min-h-full flex-col">
      <div className="border-b border-line bg-page px-4 pt-4 pb-2.5 sm:px-6">
        <p className="pb-2 text-[13px] font-semibold text-fg-strong">{title}</p>
        {nav}
      </div>
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
}
