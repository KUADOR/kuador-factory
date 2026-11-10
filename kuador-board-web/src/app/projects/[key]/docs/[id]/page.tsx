import type { Metadata } from "next";
import Link from "next/link";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { DocEditor } from "./DocEditor";

export const dynamic = "force-dynamic";

type Props = { params: Promise<{ key: string; id: string }> };

export const metadata: Metadata = { title: "Documento" };

export default async function DocumentPage({ params }: Props) {
  const { key, id } = await params;
  let data;
  try {
    data = await Promise.all([boardApi.doc(id), boardApi.docVersions(id), boardApi.members()]);
  } catch (e) {
    return failPage(e);
  }
  const [{ document, content }, versions, members] = data;
  const projectKey = key.toUpperCase();

  return (
    <div className="flex flex-col gap-4 px-6 py-5">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <Link href={`/projects/${projectKey}/docs`} className="text-xs text-fg-muted hover:text-fg-strong">
          Documentos
        </Link>
        <span aria-hidden className="text-xs text-fg-faint">/</span>
        <h2 className="text-base font-semibold text-fg-strong">{document.title}</h2>
        <code className="font-mono text-[11px] text-fg-faint">{document.path}</code>
        {document.issueKey && (
          <Link href={`/projects/${projectKey}?issue=${document.issueKey}`} className="k-link tabular font-mono text-xs">
            {document.issueKey}
          </Link>
        )}
      </div>
      <DocEditor initial={document} initialContent={content} initialVersions={versions} members={members} />
    </div>
  );
}
