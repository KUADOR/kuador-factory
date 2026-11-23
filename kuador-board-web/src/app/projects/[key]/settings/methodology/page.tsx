import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { boardApi } from "@/lib/api";
import { can } from "@/lib/labels";
import { failPage } from "@/lib/page-errors";

import { MethodologyPicker } from "../MethodologyPicker";
import { SettingsSection } from "../SettingsSection";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Metodología" };

export default async function MethodologySettingsPage({ params }: PageProps<"/projects/[key]/settings/methodology">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([boardApi.project(key), boardApi.catalog("methodologies")]);
  } catch (e) {
    return failPage(e);
  }
  const [project, methodologies] = data;
  if (!can(project, "DOCUMENTS")) notFound();
  const repository = can(project, "REPOSITORY");
  return (
    <SettingsSection title={repository ? "Metodología" : "Manual de trabajo"} description={repository ? "Cambiarla no borra documentos." : "Qué documentos lleva el tablero y cómo trabajan los agentes. Cambiarlo no borra documentos."}>
      <div className="rounded-lg border border-line bg-surface p-5">
        <MethodologyPicker project={project} methodologies={methodologies} />
      </div>
    </SettingsSection>
  );
}
