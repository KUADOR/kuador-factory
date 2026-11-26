"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ChevronRight, Plus } from "lucide-react";

import { CreateProjectForm } from "@/components/CreateProjectForm";
import { useAccess } from "@/components/shell/AccessContext";
import { PageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { Badge, EmptyState } from "@/components/ui/Section";
import { SidePanel } from "@/components/ui/SidePanel";

import { FirstSteps } from "./FirstSteps";
import { PROJECT_KIND_LABEL } from "@/lib/labels";
import type { Methodology, Project, SubjectListing, Team, WorkflowTemplate } from "@/lib/types";

/**
 * Proyectos del espacio: en el celular, tarjetas a todo el ancho (fáciles de tocar); en escritorio, una tabla. El
 * formulario de alta se abre al costado (también con ?nuevo=1).
 */
export function ProjectsView({
  projects,
  templates,
  methodologies,
  recommended = [],
  subjects = [],
  teams = [],
}: {
  subjects?: SubjectListing[];
  teams?: Team[];
  projects: Project[];
  templates: WorkflowTemplate[];
  methodologies: Methodology[];
  recommended?: string[];
}) {
  const router = useRouter();
  const { canEdit, canAdminister } = useAccess();
  const asked = useSearchParams().get("nuevo") === "1";
  const creating = canEdit && asked;
  const setCreating = (open: boolean) =>
    router.replace(open ? "/projects?nuevo=1" : "/projects", { scroll: false });
  const templateName = (key: string) => templates.find((t) => t.key === key)?.name ?? key;
  const methodologyName = (key: string | null) =>
    key ? (methodologies.find((m) => m.key === key)?.key ?? key) : null;

  return (
    <>
      <PageHeader
        title="Tableros"
        actions={
          canEdit && (
            <Button tone="primary" size="sm" icon={Plus} onClick={() => setCreating(true)}>
              Nuevo tablero
            </Button>
          )
        }
      />
      <div className="px-4 py-5 sm:px-6 sm:py-6">
        {projects.length === 0 ? (
          canEdit ? (
            <FirstSteps canDesign={canAdminister} />
          ) : (
            <EmptyState title="Aún no hay tableros">
              Cuando el equipo cree uno, lo verás aquí.
            </EmptyState>
          )
        ) : (
          <>
            <ul className="grid gap-2.5 sm:hidden">
              {projects.map((p) => (
                <li key={p.id}>
                  <Link
                    href={`/projects/${p.key}`}
                    className="flex items-center gap-3 rounded-xl border border-line bg-surface px-4 py-3.5 shadow-[var(--k-shadow)] active:bg-hover"
                  >
                    <span className="tabular grid size-11 shrink-0 place-items-center rounded-lg bg-sunken font-mono text-[12px] font-semibold text-fg-strong">
                      {p.key}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[15px] font-medium text-fg-strong">
                        {p.name}
                      </span>
                      {p.description && (
                        <span className="line-clamp-1 text-xs text-fg-muted">{p.description}</span>
                      )}
                      <span className="mt-1 flex flex-wrap items-center gap-1.5 text-[11px] text-fg-faint">
                        {templateName(p.templateKey)} · {PROJECT_KIND_LABEL[p.kind]}
                        {p.kind === "SOFTWARE" &&
                          (methodologyName(p.methodology) ? (
                            <Badge>{methodologyName(p.methodology)}</Badge>
                          ) : (
                            <Badge tone="warn">Sin manual de trabajo</Badge>
                          ))}
                      </span>
                    </span>
                    <ChevronRight className="size-4 shrink-0 text-fg-faint" aria-hidden />
                  </Link>
                </li>
              ))}
            </ul>
            <div className="hidden overflow-hidden rounded-lg border border-line bg-surface sm:block">
              <table className="w-full text-left text-[13px]">
                <thead className="border-b border-line bg-sunken text-xs text-fg-muted">
                  <tr>
                    <th scope="col" className="w-24 px-5 py-2 font-medium">
                      Clave
                    </th>
                    <th scope="col" className="px-3 py-2 font-medium">
                      Nombre
                    </th>
                    <th scope="col" className="hidden px-3 py-2 font-medium md:table-cell">
                      Plantilla
                    </th>
                    <th scope="col" className="hidden px-3 py-2 font-medium sm:table-cell">
                      Equipo
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {projects.map((p) => (
                    <tr key={p.id} className="relative hover:bg-hover">
                      <td className="tabular px-5 py-2.5 font-mono text-xs text-fg-muted">
                        {p.key}
                      </td>
                      <td className="px-3 py-2.5">
                        <Link
                          href={`/projects/${p.key}`}
                          className="font-medium text-fg-strong after:absolute after:inset-0"
                        >
                          {p.name}
                        </Link>
                        {p.description && (
                          <span className="block truncate text-xs text-fg-faint">
                            {p.description}
                          </span>
                        )}
                      </td>
                      <td className="hidden px-3 py-2.5 text-fg-muted md:table-cell">
                        {templateName(p.templateKey)}{" "}
                        <span className="text-fg-faint">· {PROJECT_KIND_LABEL[p.kind]}</span>
                      </td>
                      <td className="hidden px-3 py-2.5 text-fg-muted sm:table-cell">{p.team?.name ?? <span className="text-fg-faint">—</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
      <SidePanel
        open={creating}
        onClose={() => setCreating(false)}
        width="narrow"
        title="Nuevo tablero"
      >
        {creating && (
          <CreateProjectForm
            templates={templates}
            recommended={recommended}
            subjects={subjects}
            teams={teams}
            onCancel={() => setCreating(false)}
          />
        )}
      </SidePanel>
    </>
  );
}
