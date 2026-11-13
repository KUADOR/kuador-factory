"use client";

import Link from "next/link";
import { SectionNav } from "@/components/shell/SectionNav";

import type { ProjectKind } from "@/lib/types";

/** Secciones de la configuración del proyecto. Metodología y repositorio solo en software. */
export function SettingsNav({ projectKey, kind }: { projectKey: string; kind: ProjectKind }) {
  const base = `/projects/${projectKey}/settings`;
  const groups = [
    {
      title: "Proyecto",
      items: [
        { slug: "general", label: "General" },
        { slug: "personas", label: "Personas" },
      ],
    },
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
    <div className="flex flex-col gap-4">
      <SectionNav
        label="Configuración del proyecto"
        groups={groups.map((g) => ({ title: g.title, items: g.items.map((item) => ({ href: `${base}/${item.slug}`, label: item.label })) }))}
      />
      <Link
        href={`/projects/${projectKey}/inicio`}
        className="hidden rounded-md border border-dashed border-line-strong px-2.5 py-1.5 text-[13px] text-fg-muted hover:bg-hover hover:text-fg-strong md:block"
      >
        Asistente de inicio
      </Link>
    </div>
  );
}
