"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { clsx } from "clsx";

import { CATALOG_SECTIONS } from "./catalogTypes";

/** {@code software}: el espacio tiene tableros con repositorio; sin ellos, lo que solo sirve para código no se muestra. */
export function CatalogTabs({ base = "/catalog", software = true }: { base?: string; software?: boolean }) {
  const path = usePathname();
  return (
    <nav aria-label="Secciones del catálogo" className="-mb-px flex gap-5 overflow-x-auto">
      {CATALOG_SECTIONS.filter((s) => software || !s.software).map((s) => {
        const active = path === `${base}/${s.slug}`;
        return (
          <Link
            key={s.slug}
            href={`${base}/${s.slug}`}
            aria-current={active ? "page" : undefined}
            className={clsx("border-b-2 pb-2.5 text-[13px] whitespace-nowrap", active ? "border-sol font-medium text-fg-strong" : "border-transparent text-fg-muted hover:text-fg-strong")}
          >
            {s.label}
          </Link>
        );
      })}
    </nav>
  );
}
