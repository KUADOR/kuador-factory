import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { CatalogView } from "@/components/catalog/CatalogView";
import { sectionBySlug } from "@/components/catalog/catalogTypes";
import { boardApi } from "@/lib/api";
import { requireAdmin } from "@/lib/require-editor";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";

export async function generateMetadata({ params }: PageProps<"/catalog/[type]">): Promise<Metadata> {
  return { title: sectionBySlug((await params).type)?.label ?? "Catálogo" };
}

export default async function CatalogSectionPage({ params }: PageProps<"/catalog/[type]">) {
  await requireAdmin();
  const section = sectionBySlug((await params).type);
  if (!section) notFound();
  let data;
  try {
    // Los editores de metodologías necesitan los roles (quién escribe cada documento).
    data = await Promise.all([boardApi.catalog(section.type), boardApi.catalog("roles")]);
  } catch (e) {
    return failPage(e);
  }
  const [entries, roles] = data;
  return <CatalogView slug={section.slug} entries={entries} roles={roles} />;
}
