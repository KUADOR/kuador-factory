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
        { slug: "people", label: "Personas" },
      ],
    },
    ...(kind === "SOFTWARE"
      ? [
          {
            title: "Flujo de trabajo",
            items: [
              { slug: "columns", label: "Columnas" },
              { slug: "stages", label: "Etapas y responsables" },
              { slug: "agents", label: "Equipo de agentes" },
              { slug: "transitions", label: "Reglas de transición" },
            ],
          },
        ]
      : [
          // Fuera de software, lo de todos los días primero; etapas y reglas quedan como opciones avanzadas.
          {
            title: "Tablero",
            items: [
              { slug: "columns", label: "Columnas" },
              { slug: "agents", label: "Equipo de agentes" },
            ],
          },
          {
            title: "Avanzado",
            items: [
              { slug: "stages", label: "Etapas y aprobaciones" },
              { slug: "transitions", label: "Reglas de transición" },
            ],
          },
        ]),
    ...(kind === "SOFTWARE"
      ? [
          {
            title: "Software",
            items: [
              { slug: "methodology", label: "Metodología" },
              { slug: "repository", label: "Piezas y repositorio" },
            ],
          },
        ]
      : []),
  ];
  return (
    <div className="flex items-center gap-3">
      <div className="min-w-0 flex-1">
        <SectionNav
          label="Configuración del proyecto"
          pickerLabel="Ajustes"
          groups={groups.map((g) => ({ title: g.title, items: g.items.map((item) => ({ href: `${base}/${item.slug}`, label: item.label })) }))}
        />
      </div>
      <Link
        href={`/projects/${projectKey}/setup`}
        className="hidden shrink-0 rounded-md border border-dashed border-line-strong px-2.5 py-1.5 text-[13px] whitespace-nowrap text-fg-muted hover:bg-hover hover:text-fg-strong md:block"
      >
        Asistente de inicio
      </Link>
    </div>
  );
}
