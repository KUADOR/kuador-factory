import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { CatalogView, type PlatformInfo } from "@/components/catalog/CatalogView";
import { sectionBySlug } from "@/components/catalog/catalogTypes";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import type { AgentRole, CatalogEntries, CatalogType } from "@/lib/types";

export const dynamic = "force-dynamic";

export async function generateMetadata({ params }: PageProps<"/platform/catalog/[type]">): Promise<Metadata> {
  return { title: sectionBySlug((await params).type)?.label ?? "Catálogo de la plataforma" };
}

export default async function PlatformCatalogSectionPage({ params }: PageProps<"/platform/catalog/[type]">) {
  const section = sectionBySlug((await params).type);
  if (!section) notFound();
  let data;
  try {
    data = await Promise.all([
      boardApi.platformCatalog(section.type),
      boardApi.platformCatalog("roles"),
      section.type === "agent-policies" ? boardApi.agentRulesCore() : Promise.resolve([] as string[]),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [rows, roleRows, core] = data;
  const info: PlatformInfo = Object.fromEntries(rows.map((r) => [r.key, { status: r.status, memberName: r.memberName, color: r.color }]));
  const entries = rows.map((r) => r.entry) as CatalogEntries[CatalogType][];
  const roles = roleRows.map((r) => r.entry) as AgentRole[];
  return <CatalogView slug={section.slug} entries={entries} roles={roles} platform={info} core={core} />;
}
