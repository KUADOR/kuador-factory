"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { clsx } from "clsx";

import { useAccess } from "@/components/shell/AccessContext";
import { SectionPicker } from "@/components/shell/SectionPicker";
import type { ProjectKind } from "@/lib/types";

/** Pestañas del proyecto (navegación entre páginas). Documentos y sincronización solo en software. */
export function ProjectTabs({
  projectKey,
  kind,
  templateKey,
  variant = "tabs",
}: {
  projectKey: string;
  kind: ProjectKind;
  templateKey?: string | null;
  /** «picker»: en el celular, la sección actual al lado del nombre del proyecto; «tabs»: las pestañas de escritorio. */
  variant?: "tabs" | "picker";
}) {
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
    // Tableros de ventas: los prospectos con su próxima acción, para el celular.
    ...(templateKey === "SALES" ? [{ href: `${base}/sales`, label: "En movimiento" }] : []),
    { href: `${base}/office`, label: "Oficina" },
    { href: `${base}/activity`, label: "Actividad" },
    ...(canEdit ? [{ href: `${base}/settings`, label: "Configuración" }] : []),
  ];
  // Celular: la sección actual y, al tocar, todas; pestañas que no caben esconden «Configuración».
  if (variant === "picker")
    return <SectionPicker label="Secciones del proyecto" groups={[{ items: tabs }]} compact />;
  return (
    <>
      <nav
        aria-label="Secciones del proyecto"
        className="-mb-px hidden gap-5 overflow-x-auto md:flex"
      >
        {tabs.map((t) => {
          const active = t.exact ? path === t.href : path.startsWith(t.href);
          return (
            <Link
              key={t.href}
              href={t.href}
              aria-current={active ? "page" : undefined}
              className={clsx(
                "border-b-2 pb-2.5 text-[13px] whitespace-nowrap",
                active
                  ? "border-sol font-medium text-fg-strong"
                  : "border-transparent text-fg-muted hover:text-fg-strong",
              )}
            >
              {t.label}
            </Link>
          );
        })}
      </nav>
    </>
  );
}
