"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { clsx } from "clsx";

import type { ProjectKind } from "@/lib/types";

/** Secciones de la configuración del proyecto. Metodología y repositorio solo en software. */
export function SettingsNav({ projectKey, kind }: { projectKey: string; kind: ProjectKind }) {
  const path = usePathname();
  const base = `/projects/${projectKey}/settings`;
  const groups = [
    { title: "Proyecto", items: [{ slug: "general", label: "General" }] },
    ...(kind === "SOFTWARE"
      ? [
          {
            title: "Flujo de trabajo",
            items: [
              { slug: "columnas", label: "Columnas" },
              { slug: "etapas", label: "Etapas y responsables" },
              { slug: "equipo", label: "Equipo de agentes" },
              { slug: "transiciones", label: "Reglas de transición" },
            ],
          },
        ]
      : [
          // Fuera de software, lo de todos los días primero; etapas y reglas quedan como opciones avanzadas.
          {
            title: "Tablero",
            items: [
              { slug: "columnas", label: "Columnas" },
              { slug: "equipo", label: "Equipo de agentes" },
            ],
          },
          {
            title: "Avanzado",
            items: [
              { slug: "etapas", label: "Etapas y aprobaciones" },
              { slug: "transiciones", label: "Reglas de transición" },
            ],
          },
        ]),
    ...(kind === "SOFTWARE"
      ? [
          {
            title: "Software",
            items: [
              { slug: "metodologia", label: "Metodología" },
              { slug: "repositorio", label: "Piezas y repositorio" },
            ],
          },
        ]
      : []),
  ];
  return (
    <nav aria-label="Configuración del proyecto" className="flex flex-col gap-4">
      {groups.map((g) => (
        <div key={g.title} className="flex flex-col gap-0.5">
          <span className="px-2.5 pb-1 text-[11px] font-medium text-fg-faint">{g.title}</span>
          {g.items.map((item) => {
            const href = `${base}/${item.slug}`;
            const active = path === href;
            return (
              <Link
                key={item.slug}
                href={href}
                aria-current={active ? "page" : undefined}
                className={clsx(
                  "rounded-md px-2.5 py-1.5 text-[13px]",
                  active ? "bg-selected font-medium text-fg-strong shadow-[0_0_0_1px_var(--k-line)]" : "text-fg-muted hover:bg-hover hover:text-fg-strong",
                )}
              >
                {item.label}
              </Link>
            );
          })}
        </div>
      ))}
      <Link
        href={`/projects/${projectKey}/inicio`}
        className="rounded-md border border-dashed border-line-strong px-2.5 py-1.5 text-[13px] text-fg-muted hover:bg-hover hover:text-fg-strong"
      >
        Asistente de inicio
      </Link>
    </nav>
  );
}
