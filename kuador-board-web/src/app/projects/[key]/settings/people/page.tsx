import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { AgentsSection } from "../agents/AgentsSection";
import { SettingsSection } from "../SettingsSection";
import { PeopleEditor } from "./PeopleEditor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Quién trabaja aquí" };

export default async function BoardPeoplePage({ params }: PageProps<"/projects/[key]/settings/people">) {
  const key = (await params).key.toUpperCase();
  let people, project;
  try {
    [people, project] = await Promise.all([boardApi.boardPeople(key), boardApi.project(key)]);
  } catch (e) {
    return failPage(e);
  }
  const team = project.team?.name;
  return (
    <div className="flex flex-col gap-10">
      <SettingsSection
        title="Personas"
        description={team ? `Las del equipo «${team}»: sumar o quitar aquí cambia el equipo.` : "Quien edita o mira solo ve los tableros donde está."}
      >
        <PeopleEditor projectKey={key} people={people} />
      </SettingsSection>
      <div id="agentes" className="scroll-mt-4">
        <AgentsSection projectKey={key} />
      </div>
    </div>
  );
}
