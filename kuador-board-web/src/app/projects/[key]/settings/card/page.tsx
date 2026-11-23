import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { AdminOnly } from "../AdminOnly";
import { SettingsSection } from "../SettingsSection";
import { CardEditor } from "./CardEditor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Tarjeta" };

export default async function CardSettingsPage({ params }: PageProps<"/projects/[key]/settings/card">) {
  const key = (await params).key.toUpperCase();
  let project;
  try {
    project = await boardApi.project(key);
  } catch (e) {
    return failPage(e);
  }
  return (
    <SettingsSection title="Tarjeta" description="Cómo se llama cada tipo y qué datos lleva: monto, cliente, fecha.">
      <div className="rounded-lg border border-line bg-surface p-5">
        <AdminOnly>
          <CardEditor project={project} />
        </AdminOnly>
      </div>
    </SettingsSection>
  );
}
