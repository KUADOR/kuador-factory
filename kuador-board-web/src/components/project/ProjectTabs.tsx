"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { clsx } from "clsx";

import { useAccess } from "@/components/shell/AccessContext";
import type { ProjectKind } from "@/lib/types";

/** Pestañas del proyecto (navegación entre páginas). Documentos y sincronización solo en software. */
export function ProjectTabs({ projectKey, kind }: { projectKey: string; kind: ProjectKind }) {
  const path = usePathname();
  const { canEdit, canAdminister } = useAccess();
  const base = `/projects/${projectKey}`;
  const tabs = [
    { href: base, label: "Tablero", exact: true },
    ...(kind === "SOFTWARE"
      ? [
          { href: `${base}/docs`, label: "Documentos" },
          ...(canAdminister ? [{ href: `${base}/sync`, label: "Repositorio" }] : []),
        ]
      : []),
    { href: `${base}/activity`, label: "Actividad" },
    ...(canEdit ? [{ href: `${base}/settings`, label: "Configuración" }] : []),
  ];
  return (
    <nav aria-label="Secciones del proyecto" className="-mb-px flex gap-5 overflow-x-auto">
      {tabs.map((t) => {
        const active = t.exact ? path === t.href : path.startsWith(t.href);
        return (
          <Link
            key={t.href}
            href={t.href}
            aria-current={active ? "page" : undefined}
            className={clsx(
              "border-b-2 pb-2.5 text-[13px] whitespace-nowrap",
              active ? "border-sol font-medium text-fg-strong" : "border-transparent text-fg-muted hover:text-fg-strong",
            )}
          >
            {t.label}
          </Link>
        );
      })}
    </nav>
  );
}
