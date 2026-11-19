import { AdminOnly } from "../AdminOnly";
import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { SettingsSection } from "../SettingsSection";
import { TransitionsEditor } from "./TransitionsEditor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Reglas de transición" };

export default async function TransitionsSettingsPage({ params }: PageProps<"/projects/[key]/settings/transitions">) {
  const key = (await params).key.toUpperCase();
  let project;
  try {
    project = await boardApi.project(key);
  } catch (e) {
    return failPage(e);
  }
  return (
    <SettingsSection
      title="Reglas de transición"
      description="Por qué etapas puede pasar un issue."
    >
      <AdminOnly>
      <TransitionsEditor project={project} />
      </AdminOnly>
    </SettingsSection>
  );
}
