"use client";

import Link from "next/link";
import { SectionNav } from "@/components/shell/SectionNav";

import type { Capability } from "@/lib/types";

/** Secciones de la configuración del tablero, según lo que usa (documentos, repositorio de código). */
export function SettingsNav({ projectKey, capabilities }: { projectKey: string; capabilities: Capability[] }) {
  const repository = capabilities.includes("REPOSITORY");
  const documents = capabilities.includes("DOCUMENTS");
  const base = `/projects/${projectKey}/settings`;
  const groups = [
    {
      title: "Tablero",
      items: [
        { slug: "general", label: "General" },
        { slug: "people", label: "Personas" },
      ],
    },
    {
      title: "Flujo de trabajo",
      items: [
        { slug: "columns", label: "Pasos y responsables" },
        { slug: "card", label: "Tarjeta" },
        { slug: "agents", label: "Equipo de agentes" },
      ],
    },
    {
      title: "Automatización",
      items: [
        { slug: "routines", label: "Rutinas" },
        { slug: "inbox", label: "Correo entrante" },
      ],
    },
    ...(repository
      ? [
          {
            title: "Software",
            items: [
              { slug: "methodology", label: "Metodología" },
              { slug: "repository", label: "Piezas y repositorio" },
            ],
          },
        ]
      : documents
        ? [{ title: "Documentos", items: [{ slug: "methodology", label: "Manual de trabajo" }] }]
        : []),
    // Lo que casi nadie toca a diario queda al final.
    { title: "Avanzado", items: [{ slug: "transitions", label: "Reglas de transición" }] },
  ];
  return (
    <div className="flex items-center gap-3">
      <div className="min-w-0 flex-1">
        <SectionNav
          label="Configuración del tablero"
          pickerLabel="Configuración"
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
