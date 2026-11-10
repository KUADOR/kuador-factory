import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { MethodologyPicker } from "../MethodologyPicker";
import { SettingsSection } from "../SettingsSection";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Metodología" };

export default async function MethodologySettingsPage({ params }: PageProps<"/projects/[key]/settings/metodologia">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.project(key), boardApi.catalog("methodologies")]);
  } catch (e) {
    return failPage(e);
  }
  const [project, methodologies] = data;
  if (project.kind !== "SOFTWARE") notFound();
  return (
    <SettingsSection title="Metodología" description="Qué documentos pide el proyecto y qué escribe cada agente. Cambiarla no borra documentos.">
      <div className="rounded-lg border border-line bg-surface p-5">
        <MethodologyPicker project={project} methodologies={methodologies} />
      </div>
    </SettingsSection>
  );
}
