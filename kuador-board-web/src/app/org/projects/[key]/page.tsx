import type { Metadata } from "next";

import { SubjectGeneral } from "@/components/org/SubjectGeneral";
import { MethodologyPicker } from "@/app/projects/[key]/settings/MethodologyPicker";
import { PageBody } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { subjectHosts } from "@/lib/subject-hosts";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Proyecto" };

export default async function OrgProjectPage({ params }: PageProps<"/org/projects/[key]">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.subject(key), boardApi.projects(), boardApi.catalog("methodologies")]);
  } catch (e) {
    return failPage(e);
  }
  const [subject, boards, methodologies] = data;
  const hosts = subjectHosts(subject, boards);
  return (
    <PageBody width="narrow">
      <div className="flex flex-col gap-10">
        <SubjectGeneral subject={subject} boards={boards.filter((b) => subject.boards.includes(b.key)).map((b) => ({ key: b.key, name: b.name }))} />
        <section className="flex flex-col gap-3">
          <header>
            <h2 className="text-sm font-semibold text-fg-strong">Manual de trabajo</h2>
            <p className="mt-1 text-[13px] text-fg-muted">
              {hosts.documents.length > 0
                ? `Lo usan ${hosts.documents.map((b) => b.name).join(", ")}.`
                : "Lo usan los tableros con documentos del proyecto; ninguno trabaja este proyecto todavía."}
            </p>
          </header>
          <MethodologyPicker subject={{ key: subject.key, methodology: subject.methodology ?? null }} methodologies={methodologies} />
        </section>
      </div>
    </PageBody>
  );
}
