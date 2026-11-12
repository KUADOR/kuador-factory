"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { clsx } from "clsx";

export interface SectionNavGroup {
  title?: string;
  items: { href: string; label: string; /** Solo activo en esa ruta exacta (no en sus hijas). */ exact?: boolean }[];
}

/**
 * Submenú de una zona (Configuración, Plataforma): grupos a la izquierda en pantallas anchas y una fila desplazable
 * en el teléfono.
 */
export function SectionNav({ label, groups }: { label: string; groups: SectionNavGroup[] }) {
  const path = usePathname();
  const active = (href: string, exact?: boolean) => path === href || (!exact && path.startsWith(href + "/"));
  return (
    <nav aria-label={label} className="flex gap-1 overflow-x-auto md:flex-col md:gap-4 md:overflow-visible">
      {groups.map((g, i) => (
        <div key={g.title ?? i} className="flex shrink-0 gap-1 md:flex-col md:gap-0.5">
          {g.title && <span className="hidden px-2.5 pb-1 text-[11px] font-medium text-fg-faint md:block">{g.title}</span>}
          {g.items.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              aria-current={active(item.href, item.exact) ? "page" : undefined}
              className={clsx(
                "rounded-md px-2.5 py-1.5 text-[13px] whitespace-nowrap",
                active(item.href, item.exact) ? "bg-selected font-medium text-fg-strong" : "text-fg-muted hover:bg-hover hover:text-fg-strong",
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

/** Zona con submenú: el submenú a la izquierda y la página (con su propia cabecera) a la derecha. */
export function SectionLayout({ title, nav, children }: { title: string; nav: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="grid min-h-full grid-cols-[minmax(0,1fr)] md:grid-cols-[13.5rem_minmax(0,1fr)]">
      <aside className="border-b border-line bg-page px-3 py-3 md:border-r md:border-b-0 md:py-5">
        <p className="hidden px-2.5 pb-3 text-[13px] font-semibold text-fg-strong md:block">{title}</p>
        <div className="md:sticky md:top-5">{nav}</div>
      </aside>
      <div className="min-w-0">{children}</div>
    </div>
  );
}
