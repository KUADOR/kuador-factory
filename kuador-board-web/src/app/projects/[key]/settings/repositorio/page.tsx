import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { RepositoryForm } from "../RepositoryForm";
import { SettingsSection } from "../SettingsSection";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Repositorio" };

export default async function RepositorySettingsPage({ params }: PageProps<"/projects/[key]/settings/repositorio">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.project(key), boardApi.repository(key)]);
  } catch (e) {
    return failPage(e);
  }
  const [project, link] = data;
  if (project.kind !== "SOFTWARE") notFound();
  return (
    <SettingsSection title="Repositorio" description="El tablero lee la rama principal y propone sus cambios como Pull Requests.">
      <div className="rounded-lg border border-line bg-surface p-5">
        <RepositoryForm projectKey={project.key} link={link} />
      </div>
    </SettingsSection>
  );
}
