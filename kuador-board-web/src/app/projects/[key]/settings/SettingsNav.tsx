"use client";

import Link from "next/link";
import { SectionNav } from "@/components/shell/SectionNav";

/**
 * Secciones de la configuración del tablero: solo lo de su flujo. Su equipo, sus proyectos y lo de cada proyecto
 * (repositorio, manual, documentos) se arman en Organización.
 */
export function SettingsNav({ projectKey }: { projectKey: string }) {
  const base = `/projects/${projectKey}/settings`;
  // Los pasos (responsables, reglas) se cambian en la pestaña Flujo.
  const groups = [
    {
      items: [
        { slug: "general", label: "General" },
        { slug: "card", label: "Tarjeta" },
        { slug: "intake", label: "Entradas" },
      ],
    },
  ];
  return (
    <div className="flex items-center gap-3">
      <div className="min-w-0 flex-1">
        <SectionNav
          label="Configuración del tablero"
          pickerLabel="Configuración"
          groups={groups.map((g) => ({ items: g.items.map((item) => ({ href: `${base}/${item.slug}`, label: item.label })) }))}
        />
      </div>
      <Link
        href={`/projects/${projectKey}/setup`}
        className="shrink-0 rounded-md border border-dashed border-line-strong px-2.5 py-1.5 text-[13px] whitespace-nowrap text-fg-muted hover:bg-hover hover:text-fg-strong"
      >
        Asistente de inicio
      </Link>
    </div>
  );
}
