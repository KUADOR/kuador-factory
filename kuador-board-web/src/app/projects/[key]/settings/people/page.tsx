import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { SettingsSection } from "../SettingsSection";
import { PeopleEditor } from "./PeopleEditor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Personas del tablero" };

export default async function BoardPeoplePage({ params }: PageProps<"/projects/[key]/settings/people">) {
  const key = (await params).key.toUpperCase();
  let people;
  try {
    people = await boardApi.boardPeople(key);
  } catch (e) {
    return failPage(e);
  }
  return (
    <SettingsSection
      title="Personas del tablero"
      description="Quien edita o solo mira ve únicamente los tableros donde está. Quien administra el espacio los ve todos."
    >
      <PeopleEditor projectKey={key} people={people} />
    </SettingsSection>
  );
}
