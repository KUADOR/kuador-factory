import type { Metadata } from "next";
import Link from "next/link";
import { FileText } from "lucide-react";

import { CreateDocButton } from "@/components/docs/CreateDocButton";
import { Button } from "@/components/ui/Button";
import { Badge, EmptyState, Section } from "@/components/ui/Section";
import { canAdminister } from "@/lib/access";
import { boardApi } from "@/lib/api";
import { documentsOf } from "@/lib/labels";
import { failPage } from "@/lib/page-errors";
import type { DocumentMeta } from "@/lib/types";

export const dynamic = "force-dynamic";

export async function generateMetadata({ params }: PageProps<"/projects/[key]/docs">): Promise<Metadata> {
  return { title: `Documentos ${(await params).key.toUpperCase()}` };
}

const dateFormat = new Intl.DateTimeFormat("es-EC", { dateStyle: "medium", timeZone: "America/Guayaquil" });

/** Fila de documento: título, ruta en el repo, versión y última edición. */
function DocRow({ projectKey, doc, label }: { projectKey: string; doc: DocumentMeta; label?: string }) {
  return (
    <Link href={`/projects/${projectKey}/docs/${doc.id}`} className="grid grid-cols-[1.25rem_minmax(0,1fr)_auto] items-center gap-3 px-5 py-2.5 hover:bg-hover">
      <FileText className="size-4 text-fg-faint" aria-hidden />
      <span className="min-w-0">
        <span className="block truncate text-[13px] font-medium text-fg-strong">{label ?? doc.title}</span>
        <code className="block truncate font-mono text-[11px] text-fg-faint">{doc.path}</code>
      </span>
      <span className="tabular text-right font-mono text-[11px] text-fg-muted">
        v{doc.currentVersion} · {dateFormat.format(new Date(doc.updatedAt))}
      </span>
    </Link>
  );
}

export default async function ProjectDocsPage({ params }: PageProps<"/projects/[key]/docs">) {
  const projectKey = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.project(projectKey), boardApi.docsOverview(projectKey), boardApi.issues(projectKey), boardApi.me().catch(() => null)]);
  } catch (e) {
    return failPage(e);
  }
  const [project, overview, issues, me] = data;
  // Sincronizar es de quien administra y solo existe con repositorio: a los demás el enlace los devolvía sin explicación.
  const canSync = me != null && canAdminister(me) && project.capabilities.includes("REPOSITORY");
  const { methodology, documents } = overview;
  const settings = `/projects/${project.key}/settings`;

  if (!methodology) {
    return (
      <div className="max-w-3xl px-4 py-5 sm:px-6 sm:py-6">
        <EmptyState
          title={project.capabilities.includes("REPOSITORY") ? "Este tablero aún no tiene metodología" : "Este tablero aún no tiene manual de trabajo"}
          action={<Button href={`${settings}/methodology`} tone="primary" size="sm">{project.capabilities.includes("REPOSITORY") ? "Elegir metodología" : "Elegir manual"}</Button>}
        />
      </div>
    );
  }

  const projectRules = documentsOf(methodology, "PROJECT");
  const issueRules = documentsOf(methodology, "ISSUE");
  const projectDocs = documents.filter((d) => !d.issueKey);
  // Despliegue lo trae el tablero en toda metodología: documento vivo, opcional, que se actualiza con el proyecto.
  const expected = new Set([...projectRules.map((r) => r.kind), "DEPLOYMENT"]);
  const others = projectDocs.filter((d) => !expected.has(d.kind));
  const deployment = projectRules.some((r) => r.kind === "DEPLOYMENT") ? null : projectDocs.find((d) => d.kind === "DEPLOYMENT");
  const deploymentDraft = `/projects/${project.key}/docs/draft?doc=DEPLOYMENT`;
  const titles = new Map(issues.map((i) => [i.key, i.title]));
  const byIssue = Map.groupBy(documents.filter((d) => d.issueKey), (d) => d.issueKey as string);

  return (
    <div className="flex flex-col gap-5 px-4 py-5 sm:px-6 sm:py-6">
      <p className="text-[13px] text-fg-muted">
        Metodología <span className="font-medium text-fg-strong">{methodology.name}</span>.{" "}
        <Link href={`${settings}/methodology`} className="k-link">
          Cambiar
        </Link>
        {canSync && (
          <>
            {" · "}
            <Link href={`/projects/${project.key}/sync`} className="k-link">
              Sincronizar con el repositorio
            </Link>
          </>
        )}
      </p>

      <Section
        title="Del tablero"
        actions={
          <Button href={`/projects/${project.key}/docs/draft`} tone={projectRules.some((r) => !projectDocs.some((d) => d.kind === r.kind)) ? "primary" : "secondary"} size="sm">
            Redactar con IA
          </Button>
        }
        flush
      >
        <ul className="divide-y divide-line">
          {projectRules.map((rule) => {
            const doc = projectDocs.find((d) => d.kind === rule.kind);
            return (
              <li key={rule.kind}>
                {doc ? (
                  <DocRow projectKey={project.key} doc={doc} label={rule.label} />
                ) : (
                  <div className="grid grid-cols-[1.25rem_minmax(0,1fr)_auto] items-center gap-3 px-5 py-2.5">
                    <FileText className="size-4 text-fg-faint/50" aria-hidden />
                    <span className="min-w-0">
                      <span className="flex items-center gap-2 text-[13px] text-fg">
                        {rule.label}
                        {rule.required && <Badge tone="warn">Obligatorio</Badge>}
                      </span>
                      <span className="block truncate text-xs text-fg-faint">{rule.purpose}</span>
                    </span>
                    <CreateDocButton projectKey={project.key} kind={rule.kind} label="Crear" compact />
                  </div>
                )}
              </li>
            );
          })}
          {project.capabilities.includes("REPOSITORY") && !projectRules.some((r) => r.kind === "DEPLOYMENT") && (
            <li className="flex items-center">
              {deployment ? (
                <div className="min-w-0 flex-1">
                  <DocRow projectKey={project.key} doc={deployment} label="Despliegue" />
                </div>
              ) : (
                <div className="grid min-w-0 flex-1 grid-cols-[1.25rem_minmax(0,1fr)] items-center gap-3 py-2.5 pl-5">
                  <FileText className="size-4 text-fg-faint/50" aria-hidden />
                  <span className="min-w-0">
                    <span className="block text-[13px] text-fg">Despliegue</span>
                    <span className="block truncate text-xs text-fg-faint">Dónde y cómo se despliega cada entorno. Opcional.</span>
                  </span>
                </div>
              )}
              <Link href={deploymentDraft} className="k-link shrink-0 px-5 text-xs">
                {deployment ? "Actualizar con IA" : "Redactar con IA"}
              </Link>
            </li>
          )}
          {others.map((doc) => (
            <li key={doc.id}>
              <DocRow projectKey={project.key} doc={doc} />
            </li>
          ))}
        </ul>
      </Section>

      <Section
        title="De las tarjetas"
        description={issueRules.map((r) => r.label).join(", ") || "Esta metodología no pide documentos por tarjeta."}
        flush
      >
        {byIssue.size === 0 ? (
          <p className="px-5 py-4 text-[13px] text-fg-faint">Aún no hay documentos de tarjetas.</p>
        ) : (
          <ul className="divide-y divide-line">
            {[...byIssue.entries()].map(([issueKey, docs]) => (
              <li key={issueKey} className="py-1.5">
                <Link href={`/projects/${project.key}?issue=${issueKey}`} className="flex items-center gap-2 px-5 pt-1.5 pb-0.5 text-xs text-fg-muted hover:text-fg-strong">
                  <span className="tabular font-mono text-fg-strong">{issueKey}</span>
                  <span className="truncate">{titles.get(issueKey) ?? "(tarjeta eliminada)"}</span>
                </Link>
                {docs.map((doc) => (
                  <DocRow key={doc.id} projectKey={project.key} doc={doc} />
                ))}
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}
