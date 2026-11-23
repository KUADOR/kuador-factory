import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { canAdminister } from "@/lib/access";
import { roleLabel, rolesForBoard, stageRoleOptions } from "@/lib/labels";
import { failPage } from "@/lib/page-errors";
import type { BusinessConnection, MailInbox, Routine, TeamAgent } from "@/lib/types";

import { FlowView } from "./FlowView";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Flujo" };

/**
 * El flujo del tablero de un vistazo: de dónde llega el trabajo, por qué pasos va, quién hace cada uno, qué sistemas
 * usa y a dónde sale. Quien administra edita cada paso ahí mismo.
 */
export default async function FlowPage({ params }: PageProps<"/projects/[key]/flow">) {
  const key = (await params).key.toUpperCase();
  let data;
  try {
    data = await Promise.all([
      boardApi.me(),
      boardApi.project(key),
      boardApi.issues(key),
      boardApi.boardTeam(key).catch(() => [] as TeamAgent[]),
      boardApi.inboxes(key).catch(() => [] as MailInbox[]),
      boardApi.routines(key).catch(() => [] as Routine[]),
      boardApi.connections().catch(() => [] as BusinessConnection[]),
      boardApi.catalog("roles").catch(() => []),
      boardApi.members().catch(() => []),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [me, project, issues, team, inboxes, routines, connections, roles, members] = data;
  const counts: Record<string, number> = {};
  issues.forEach((i) => (counts[i.status] = (counts[i.status] ?? 0) + 1));
  const software = project.capabilities.includes("REPOSITORY");
  const humanRoles = [...new Set(["OWNER", ...members.filter((m) => m.kind === "HUMAN").map((m) => m.role)])];
  const boardConnections = connections
    .filter((c) => c.boards.some((b) => b.projectKey === key))
    .map((c) => ({
      key: c.key,
      name: c.name,
      roles: c.boards.find((b) => b.projectKey === key)?.roles ?? [],
      writes: c.tools.filter((t) => !t.readOnly && t.allowed).map((t) => t.name),
    }));
  return (
    <FlowView
      project={project}
      counts={counts}
      agents={team.filter((a) => a.inTeam).map((a) => ({ name: a.name, role: a.role }))}
      inboxes={inboxes}
      routines={routines}
      connections={boardConnections}
      roleOptions={stageRoleOptions(rolesForBoard(roles, software), humanRoles)}
      roleName={Object.fromEntries([...roles.map((r) => [r.key, r.name]), ...humanRoles.map((k) => [k, roleLabel(k)])])}
      canEdit={canAdminister(me)}
    />
  );
}
