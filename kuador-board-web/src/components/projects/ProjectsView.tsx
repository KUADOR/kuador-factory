"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Plus } from "lucide-react";

import { CreateProjectForm } from "@/components/CreateProjectForm";
import { useAccess } from "@/components/shell/AccessContext";
import { PageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { Badge, EmptyState } from "@/components/ui/Section";
import { SidePanel } from "@/components/ui/SidePanel";
import { PROJECT_KIND_LABEL } from "@/lib/labels";
import type { Methodology, Project, WorkflowTemplate } from "@/lib/types";

/** Proyectos del espacio en una tabla; el formulario de alta se abre al costado (también con ?nuevo=1). */
export function ProjectsView({ projects, templates, methodologies }: { projects: Project[]; templates: WorkflowTemplate[]; methodologies: Methodology[] }) {
  const router = useRouter();
  const { canEdit } = useAccess();
  const asked = useSearchParams().get("nuevo") === "1";
  const creating = canEdit && asked;
  const setCreating = (open: boolean) => router.replace(open ? "/?nuevo=1" : "/", { scroll: false });
  const templateName = (key: string) => templates.find((t) => t.key === key)?.name ?? key;
  const methodologyName = (key: string | null) => (key ? (methodologies.find((m) => m.key === key)?.key ?? key) : null);

  return (
    <>
      <PageHeader
        title="Proyectos"
        actions={
          canEdit && (
            <Button tone="primary" size="sm" icon={Plus} onClick={() => setCreating(true)}>
              Nuevo proyecto
            </Button>
          )
        }
      />
      <div className="px-6 py-6">
        {projects.length === 0 ? (
          canEdit ? (
            <EmptyState title="Crea el primer proyecto" action={<Button tone="primary" size="sm" icon={Plus} onClick={() => setCreating(true)}>Nuevo proyecto</Button>}>
              Elige una plantilla del catálogo (software, ventas, tareas o una propia). Las columnas y los tipos de trabajo salen de ahí y se pueden ajustar después.
            </EmptyState>
          ) : (
            <EmptyState title="Aún no hay proyectos">Cuando el equipo cree uno, lo verás aquí.</EmptyState>
          )
        ) : (
          <div className="overflow-hidden rounded-lg border border-line bg-surface">
            <table className="w-full text-left text-[13px]">
              <thead className="border-b border-line bg-sunken text-xs text-fg-muted">
                <tr>
                  <th scope="col" className="w-24 px-5 py-2 font-medium">Clave</th>
                  <th scope="col" className="px-3 py-2 font-medium">Nombre</th>
                  <th scope="col" className="hidden px-3 py-2 font-medium md:table-cell">Plantilla</th>
                  <th scope="col" className="hidden px-3 py-2 font-medium sm:table-cell">Metodología</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {projects.map((p) => (
                  <tr key={p.id} className="relative hover:bg-hover">
                    <td className="tabular px-5 py-2.5 font-mono text-xs text-fg-muted">{p.key}</td>
                    <td className="px-3 py-2.5">
                      <Link href={`/projects/${p.key}`} className="font-medium text-fg-strong after:absolute after:inset-0">
                        {p.name}
                      </Link>
                      {p.description && <span className="block truncate text-xs text-fg-faint">{p.description}</span>}
                    </td>
                    <td className="hidden px-3 py-2.5 text-fg-muted md:table-cell">
                      {templateName(p.templateKey)} <span className="text-fg-faint">· {PROJECT_KIND_LABEL[p.kind]}</span>
                    </td>
                    <td className="hidden px-3 py-2.5 sm:table-cell">
                      {p.kind !== "SOFTWARE" ? (
                        <span className="text-fg-faint">—</span>
                      ) : methodologyName(p.methodology) ? (
                        <Badge>{methodologyName(p.methodology)}</Badge>
                      ) : (
                        <Badge tone="warn">Sin elegir</Badge>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <SidePanel open={creating} onClose={() => setCreating(false)} width="narrow" title="Nuevo proyecto">
        {creating && <CreateProjectForm templates={templates} onCancel={() => setCreating(false)} />}
      </SidePanel>
    </>
  );
}
