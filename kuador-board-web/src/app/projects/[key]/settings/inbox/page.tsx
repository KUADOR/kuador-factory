import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import type { TeamAgent } from "@/lib/types";

import { SettingsSection } from "../SettingsSection";
import { InboxEditor } from "./InboxEditor";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Correo entrante" };

export default async function InboxPage({ params }: PageProps<"/projects/[key]/settings/inbox">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([
      boardApi.inboxes(key),
      boardApi.mailboxes(),
      boardApi.project(key),
      boardApi.boardTeam(key).catch(() => [] as TeamAgent[]),
      boardApi.boardPeople(key).catch(() => null),
      boardApi.members(),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [inboxes, mailboxes, project, team, people, members] = data;
  const inBoard = people == null ? null : new Set(people.filter((p) => p.inBoard).map((p) => p.id));
  const teamIds = new Set(team.filter((a) => a.inTeam).map((a) => a.id));
  const assignees = members
    .filter((m) => (m.kind === "AGENT" ? teamIds.has(m.id) : inBoard == null || inBoard.has(m.id)))
    .map((m) => ({ id: m.id, name: m.name, agent: m.kind === "AGENT" }));
  return (
    <SettingsSection title="Correo entrante" description="Cada correo nuevo del buzón que elijas se vuelve una tarjeta, con su texto y sus adjuntos.">
      <InboxEditor
        projectKey={key}
        inboxes={inboxes}
        mailboxes={mailboxes}
        columns={project.columns.filter((c) => c.category !== "DONE").map((c) => ({ key: c.key, name: c.name }))}
        assignees={assignees}
      />
    </SettingsSection>
  );
}
