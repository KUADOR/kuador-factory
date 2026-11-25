"use client";

import { clsx } from "clsx";
import { ArrowDown, ArrowUp, Search, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { SelectInput, TextInput } from "@/components/ui/Field";
import { PriorityIcon, TypeIcon } from "@/components/ui/IssueIcons";
import { PRIORITY_LABEL, allowedTypes, roleLabel, typeLabel } from "@/lib/labels";
import type { AgentMode, AgentRole, AgentRun, Issue, IssueListQuery, IssuePage, IssueSort, IssueType, Me, Member, Project } from "@/lib/types";

import { IssueDetail } from "./IssueDetail";
import { ViewSwitch } from "./ViewSwitch";

const DATE = new Intl.DateTimeFormat("es", { day: "numeric", month: "short", year: "numeric" });

/**
 * Todas las tarjetas del tablero, también las terminadas que el tablero ya no muestra. Los filtros, el orden y la
 * página viven en la URL y los resuelve la API: se puede compartir el enlace y «atrás» funciona.
 */
export function IssueList({
  project,
  page,
  query,
  members,
  everyone,
  epics,
  runs,
  me,
  roles,
  modes,
}: {
  project: Project;
  page: IssuePage;
  query: IssueListQuery;
  members: Member[];
  everyone: Member[];
  epics: Issue[];
  runs: AgentRun[];
  me: Me;
  roles: AgentRole[];
  modes: Record<string, AgentMode>;
}) {
  const router = useRouter();
  const roleName = (key: string) => roleLabel(key, roles.find((r) => r.key === key)?.name);
  const memberById = new Map(everyone.map((m) => [m.id, m]));
  const columnName = new Map(project.columns.map((c) => [c.key, c.name]));
  const [openKey, setOpenKey] = useState<string | null>(null);
  const openIssue = openKey ? page.items.find((i) => i.key === openKey) : undefined;

  /** La misma lista con otros filtros; cambiar un filtro vuelve a la primera página. */
  const href = (change: Partial<IssueListQuery>) => {
    const next = { ...query, page: 0, ...change };
    const params = new URLSearchParams({ view: "list" });
    for (const [k, v] of Object.entries(next)) {
      const fallback = k === "sort" ? "updated" : k === "dir" ? "desc" : k === "page" ? 0 : undefined;
      if (v !== undefined && v !== "" && v !== fallback) params.set(k, String(v));
    }
    return `/projects/${project.key}?${params}`;
  };
  const go = (change: Partial<IssueListQuery>) => router.push(href(change), { scroll: false });

  // El texto se busca al dejar de escribir, no en cada tecla.
  const [text, setText] = useState(query.q ?? "");
  useEffect(() => {
    const wanted = text.trim();
    if (wanted === (query.q ?? "")) return;
    const timer = setTimeout(() => go({ q: wanted || undefined }), 350);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- solo cuando cambia lo escrito
  }, [text]);

  const filtering = Boolean(query.q || query.status || query.assigneeId || query.type || query.label);
  const from = page.total === 0 ? 0 : page.page * page.size + 1;
  const to = Math.min(page.total, (page.page + 1) * page.size);
  const pages = Math.max(1, Math.ceil(page.total / page.size));

  const sortable = (sort: IssueSort, label: string, className?: string) => {
    const active = (query.sort ?? "updated") === sort;
    const descending = (query.dir ?? "desc") === "desc";
    const Arrow = descending ? ArrowDown : ArrowUp;
    return (
      <th scope="col" aria-sort={active ? (descending ? "descending" : "ascending") : "none"} className={clsx("px-3 py-2 font-medium", className)}>
        <Link href={href({ sort, dir: active && !descending ? "desc" : active ? "asc" : sort === "updated" ? "desc" : "asc" })} scroll={false} className="inline-flex items-center gap-1 hover:text-fg-strong">
          {label}
          {active && <Arrow className="size-3" aria-hidden />}
        </Link>
      </th>
    );
  };

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <div className="flex flex-wrap items-center gap-2 px-4 py-2.5 sm:px-6 md:py-3">
        <div className="relative min-w-[11rem] flex-1 md:w-56 md:flex-none">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 z-10 size-4 -translate-y-1/2 text-fg-faint" aria-hidden />
          <TextInput size="sm" value={text} onChange={(e) => setText(e.target.value)} placeholder="Buscar por título o clave" aria-label="Buscar tarjetas" className="pl-8" />
        </div>
        {/* En celular va junto a la búsqueda; en escritorio, al final de la fila. */}
        <ViewSwitch projectKey={project.key} view="list" className="md:order-last md:ml-auto" />
        <div className="min-w-0 basis-[30%] flex-1 md:w-40 md:flex-none md:basis-auto">
          <SelectInput
            size="sm"
            ariaLabel="Filtrar por columna"
            placeholder="Todas las columnas"
            options={project.columns.map((c) => ({ value: c.key, label: c.name }))}
            value={query.status ?? null}
            onChange={(v) => go({ status: v ?? undefined })}
          />
        </div>
        <div className="min-w-0 basis-[30%] flex-1 md:w-44 md:flex-none md:basis-auto">
          <SelectInput
            size="sm"
            ariaLabel="Filtrar por responsable"
            placeholder="Cualquier responsable"
            filter
            options={members.map((m) => ({ value: m.id, label: m.name, leading: <Avatar member={m} size="xs" /> }))}
            value={query.assigneeId ?? null}
            onChange={(v) => go({ assigneeId: v ?? undefined })}
          />
        </div>
        <div className="min-w-0 basis-[30%] flex-1 md:w-40 md:flex-none md:basis-auto">
          <SelectInput
            size="sm"
            ariaLabel="Filtrar por tipo"
            placeholder="Todos los tipos"
            options={allowedTypes(project).map((t) => ({ value: t, label: typeLabel(t, project), leading: <TypeIcon type={t} projectType={project} /> }))}
            value={(query.type as IssueType | undefined) ?? null}
            onChange={(v) => go({ type: v ?? undefined })}
          />
        </div>
        {query.label && (
          <button type="button" onClick={() => go({ label: undefined })} className="flex items-center gap-1 rounded-full bg-sol/15 px-2 py-1 text-xs text-fg-strong" title="Quitar el filtro por etiqueta">
            {query.label}
            <X className="size-3" aria-hidden />
          </button>
        )}
        {filtering && (
          <Button tone="ghost" size="sm" icon={X} href={`/projects/${project.key}?view=list`} onClick={() => setText("")}>
            Limpiar filtros
          </Button>
        )}
      </div>

      <div className="min-h-0 flex-1 overflow-auto px-4 pb-3 sm:px-6">
        <table className="w-full min-w-[640px] border-separate border-spacing-0 text-left text-[13px]">
          <thead className="sticky top-0 z-10 bg-page text-xs text-fg-faint">
            <tr>
              {sortable("key", "Clave", "w-24")}
              {sortable("title", "Título")}
              {sortable("status", "Columna", "w-40")}
              <th scope="col" className="w-44 px-3 py-2 font-medium">Responsable</th>
              {sortable("priority", "Prioridad", "w-28")}
              {sortable("updated", "Actualizada", "w-32")}
            </tr>
          </thead>
          <tbody>
            {page.items.map((issue) => {
              const assignee = issue.assigneeId ? memberById.get(issue.assigneeId) : undefined;
              return (
                <tr key={issue.key} onClick={() => setOpenKey(issue.key)} className="cursor-pointer hover:bg-hover">
                  <td className="tabular border-t border-line px-3 py-2 font-mono text-xs whitespace-nowrap text-fg-muted">{issue.key}</td>
                  <td className="border-t border-line px-3 py-2">
                    <span className="flex min-w-0 items-center gap-2">
                      <TypeIcon type={issue.type} projectType={project} />
                      <button type="button" onClick={() => setOpenKey(issue.key)} className="min-w-0 truncate text-left text-fg-strong hover:underline">
                        {issue.title}
                      </button>
                      {issue.labels.map((l) => (
                        <Link
                          key={l}
                          href={href({ label: l })}
                          scroll={false}
                          onClick={(e) => e.stopPropagation()}
                          className="shrink-0 rounded-full bg-sunken px-1.5 py-0.5 text-[11px] text-fg-muted hover:text-fg-strong"
                        >
                          {l}
                        </Link>
                      ))}
                    </span>
                  </td>
                  <td className="border-t border-line px-3 py-2 text-fg-muted">{columnName.get(issue.status) ?? issue.status}</td>
                  <td className="border-t border-line px-3 py-2">
                    {assignee ? (
                      <span className="flex min-w-0 items-center gap-1.5">
                        <Avatar member={assignee} size="xs" />
                        <span className="truncate text-fg">{assignee.name}</span>
                      </span>
                    ) : (
                      <span className="text-fg-faint">Sin asignar</span>
                    )}
                  </td>
                  <td className="border-t border-line px-3 py-2">
                    <span className="flex items-center gap-1.5 text-fg-muted">
                      <PriorityIcon priority={issue.priority} />
                      {PRIORITY_LABEL[issue.priority]}
                    </span>
                  </td>
                  <td className="tabular border-t border-line px-3 py-2 text-fg-muted">{DATE.format(new Date(issue.updatedAt))}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {page.items.length === 0 && (
          <p className="px-3 py-10 text-center text-[13px] text-fg-muted">
            {filtering ? "Ninguna tarjeta coincide con esos filtros." : "Este tablero todavía no tiene tarjetas."}
          </p>
        )}
      </div>

      <div className="flex items-center gap-3 border-t border-line px-4 py-2 text-xs text-fg-muted sm:px-6">
        <span className="tabular" role="status">
          {from}–{to} de {page.total}
        </span>
        <span className="ml-auto tabular">
          Página {page.page + 1} de {pages}
        </span>
        <Button tone="secondary" size="sm" href={page.page > 0 ? href({ page: page.page - 1 }) : undefined} disabled={page.page === 0}>
          Anterior
        </Button>
        <Button tone="secondary" size="sm" href={page.page + 1 < pages ? href({ page: page.page + 1 }) : undefined} disabled={page.page + 1 >= pages}>
          Siguiente
        </Button>
      </div>

      {openIssue && (
        <IssueDetail
          key={openIssue.key}
          issue={openIssue}
          project={project}
          members={members}
          epics={epics}
          me={me}
          roleName={roleName}
          onOpenIssue={(key) => (page.items.some((i) => i.key === key) ? setOpenKey(key) : router.push(`/projects/${project.key}?issue=${encodeURIComponent(key)}`))}
          onClose={() => setOpenKey(null)}
          modes={modes}
          everyone={everyone}
          lastRun={runs.filter((r) => r.issueKey === openIssue.key).sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0]}
        />
      )}
    </div>
  );
}
