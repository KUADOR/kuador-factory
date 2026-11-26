import type { Metadata } from "next";
import Link from "next/link";

import { DocEditor } from "@/app/projects/[key]/docs/[id]/DocEditor";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Documento" };

export default async function OrgProjectDocumentPage({ params }: PageProps<"/org/projects/[key]/docs/[id]">) {
  const { key, id } = await params;
  let data;
  try {
    data = await Promise.all([boardApi.doc(id), boardApi.docVersions(id), boardApi.members()]);
  } catch (e) {
    return failPage(e);
  }
  const [{ document, content }, versions, members] = data;
  return (
    <div className="flex flex-col gap-4 px-6 py-5">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <Link href={`/org/projects/${key.toUpperCase()}/docs`} className="text-xs text-fg-muted hover:text-fg-strong">
          Documentos base
        </Link>
        <span aria-hidden className="text-xs text-fg-faint">/</span>
        <h2 className="text-base font-semibold text-fg-strong">{document.title}</h2>
        <code className="font-mono text-[11px] text-fg-faint">{document.path}</code>
      </div>
      <DocEditor initial={document} initialContent={content} initialVersions={versions} members={members} />
    </div>
  );
}
