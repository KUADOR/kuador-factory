import type { Metadata } from "next";
import Link from "next/link";
import { FileText } from "lucide-react";

import { CreateDocButton } from "@/components/docs/CreateDocButton";
import { Button } from "@/components/ui/Button";
import { Badge, EmptyState, Section } from "@/components/ui/Section";
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
    data = await Promise.all([boardApi.project(projectKey), boardApi.docsOverview(projectKey), boardApi.issues(projectKey)]);
  } catch (e) {
    return failPage(e);
  }
  const [project, overview, issues] = data;
  const { methodology, documents } = overview;
  const settings = `/projects/${project.key}/settings`;

  if (!methodology) {
    return (
      <div className="max-w-3xl px-6 py-6">
        <EmptyState title="Este proyecto aún no tiene metodología" action={<Button href={settings} tone="primary" size="sm">Elegir metodología</Button>}>
          La metodología (SDD, ODD, Libre o una propia del catálogo) decide qué documentos pide el proyecto, dónde viven en el repositorio y qué escribe cada agente.
        </EmptyState>
      </div>
    );
  }

  const projectRules = documentsOf(methodology, "PROJECT");
  const issueRules = documentsOf(methodology, "ISSUE");
  const projectDocs = documents.filter((d) => !d.issueKey);
  const expected = new Set(projectRules.map((r) => r.kind));
  const others = projectDocs.filter((d) => !expected.has(d.kind));
  const titles = new Map(issues.map((i) => [i.key, i.title]));
  const byIssue = Map.groupBy(documents.filter((d) => d.issueKey), (d) => d.issueKey as string);

  return (
    <div className="flex flex-col gap-5 px-6 py-6">
      <p className="text-[13px] text-fg-muted">
        Metodología <span className="font-medium text-fg-strong">{methodology.name}</span>.{" "}
        <Link href={settings} className="k-link">
          Cambiar
        </Link>
        {" · "}
        <Link href={`/projects/${project.key}/sync`} className="k-link">
          Sincronizar con el repositorio
        </Link>
      </p>

      <Section title="Del proyecto" description="Contexto que leen todas las personas y agentes antes de trabajar." flush>
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
          {others.map((doc) => (
            <li key={doc.id}>
              <DocRow projectKey={project.key} doc={doc} />
            </li>
          ))}
        </ul>
      </Section>

      <Section
        title="De los issues"
        description={`Se crean desde la pestaña Documentos de cada issue: ${issueRules.map((r) => r.label).join(", ") || "esta metodología no pide documentos por issue"}.`}
        flush
      >
        {byIssue.size === 0 ? (
          <p className="px-5 py-4 text-[13px] text-fg-faint">Aún no hay documentos de issues.</p>
        ) : (
          <ul className="divide-y divide-line">
            {[...byIssue.entries()].map(([issueKey, docs]) => (
              <li key={issueKey} className="py-1.5">
                <Link href={`/projects/${project.key}?issue=${issueKey}`} className="flex items-center gap-2 px-5 pt-1.5 pb-0.5 text-xs text-fg-muted hover:text-fg-strong">
                  <span className="tabular font-mono text-fg-strong">{issueKey}</span>
                  <span className="truncate">{titles.get(issueKey) ?? "(issue eliminado)"}</span>
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
