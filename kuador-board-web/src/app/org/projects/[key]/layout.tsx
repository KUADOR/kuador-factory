import { PageHeader } from "@/components/shell/PageHeader";
import { SectionNav } from "@/components/shell/SectionNav";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { subjectHosts } from "@/lib/subject-hosts";

/** Un proyecto con todo lo suyo: lo general (y su manual), su repositorio y sus documentos base. */
export default async function OrgProjectLayout({ params, children }: LayoutProps<"/org/projects/[key]">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.subject(key), boardApi.projects()]);
  } catch (e) {
    return failPage(e);
  }
  const [subject, boards] = data;
  const hosts = subjectHosts(subject, boards);
  const base = `/org/projects/${subject.key}`;
  const items = [
    { href: base, label: "General", exact: true },
    ...(hosts.code.length > 0 ? [{ href: `${base}/repository`, label: "Repositorio" }] : []),
    ...(hosts.documents.length > 0 ? [{ href: `${base}/docs`, label: "Documentos base" }] : []),
  ];
  return (
    <>
      <PageHeader title={subject.name} crumbs={[{ label: "Proyectos", href: "/org/projects" }]}>
        {items.length > 1 && (
          <div className="pt-2 pb-2">
            <SectionNav label={`Secciones de ${subject.name}`} pickerLabel="Sección" groups={[{ items }]} />
          </div>
        )}
      </PageHeader>
      {children}
    </>
  );
}
