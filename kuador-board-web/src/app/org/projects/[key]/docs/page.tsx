import type { Metadata } from "next";
import Link from "next/link";
import { FileText } from "lucide-react";

import { CreateDocButton } from "@/components/docs/CreateDocButton";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Section";
import { boardApi, projectScope } from "@/lib/api";
import { documentsOf } from "@/lib/labels";
import { failPage } from "@/lib/page-errors";
import { NoDocumentsBoard } from "@/components/org/NoDocumentsBoard";
import { subjectHosts } from "@/lib/subject-hosts";
import type { DocumentMeta } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Documentos base" };

const dateFormat = new Intl.DateTimeFormat("es-EC", { dateStyle: "medium", timeZone: "America/Guayaquil" });

/** Los documentos base del proyecto, los que pide su manual: se redactan una vez y los leen todos sus tableros. */
export default async function OrgProjectDocsPage({ params }: PageProps<"/org/projects/[key]/docs">) {
  const key = (await params).key.toUpperCase();
  const base = `/org/projects/${key}/docs`;
  const [subjectInfo, boards] = await Promise.all([boardApi.subject(key), boardApi.projects()]).catch(() => [null, []] as const);
  if (subjectInfo && subjectHosts(subjectInfo, [...boards]).documents.length === 0) return <NoDocumentsBoard />;
  let overview;
  try {
    overview = await boardApi.docsOverview(projectScope(key));
  } catch (e) {
    return failPage(e);
  }
  const { methodology, documents } = overview;
  if (!methodology) {
    return (
      <p className="px-4 py-5 text-[13px] text-fg-muted sm:px-6">
        Este proyecto aún no tiene manual de trabajo.{" "}
        <Link href={`/org/projects/${key}`} className="k-link">
          Elegirlo
        </Link>
      </p>
    );
  }
  const rules = documentsOf(methodology, "PROJECT");
  const projectDocs = documents.filter((d) => !d.issueKey);
  const missing = rules.some((r) => !projectDocs.some((d) => d.kind === r.kind));
  const extra = projectDocs.filter((d) => !rules.some((r) => r.kind === d.kind));
  return (
    <div className="flex max-w-4xl flex-col gap-4 px-4 py-5 sm:px-6 sm:py-6">
      <div className="flex items-center justify-between gap-3">
        <p className="text-[13px] text-fg-muted">
          Manual <span className="font-medium text-fg-strong">{methodology.name}</span>.
        </p>
        <Button href={`${base}/draft`} tone={missing ? "primary" : "secondary"} size="sm">
          Redactar con IA
        </Button>
      </div>
      <ul className="divide-y divide-line overflow-hidden rounded-lg border border-line bg-surface">
        {rules.map((rule) => {
          const doc = projectDocs.find((d) => d.kind === rule.kind);
          return (
            <li key={rule.kind}>
              {doc ? (
                <DocRow href={`${base}/${doc.id}`} doc={doc} label={rule.label} />
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
                  <CreateDocButton projectKey={projectScope(key)} kind={rule.kind} label="Crear" compact docsBase={base} />
                </div>
              )}
            </li>
          );
        })}
        {extra.map((doc) => (
          <li key={doc.id}>
            <DocRow href={`${base}/${doc.id}`} doc={doc} />
          </li>
        ))}
      </ul>
    </div>
  );
}

function DocRow({ href, doc, label }: { href: string; doc: DocumentMeta; label?: string }) {
  return (
    <Link href={href} className="grid grid-cols-[1.25rem_minmax(0,1fr)_auto] items-center gap-3 px-5 py-2.5 hover:bg-hover">
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
