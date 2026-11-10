"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { clsx } from "clsx";

import { CATALOG_SECTIONS } from "./catalogTypes";

export function CatalogTabs() {
  const path = usePathname();
  return (
    <nav aria-label="Secciones del catálogo" className="-mb-px flex gap-5 overflow-x-auto">
      {CATALOG_SECTIONS.map((s) => {
        const active = path === `/catalog/${s.slug}`;
        return (
          <Link
            key={s.slug}
            href={`/catalog/${s.slug}`}
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
