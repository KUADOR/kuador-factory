import type { Metadata } from "next";

import { TeamView } from "@/components/team/TeamView";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Miembros" };

export default async function TeamPage() {
  let data;
  try {
    data = await Promise.all([
      boardApi.members(),
      // Correos, tokens e invitaciones solo los ve quien administra; para el resto, la API responde 403.
      boardApi.accounts().catch(() => []),
      boardApi.agentTokens().catch(() => []),
      boardApi.agentsStatus(),
      boardApi.agentRuns({ limit: 100 }),
      boardApi.catalog("roles"),
      boardApi.invitations().catch(() => []),
      boardApi.projects().catch(() => []),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [members, accounts, tokens, status, runs, roles, invitations, projects] = data;
  return (
    <TeamView members={members} accounts={accounts} tokens={tokens} status={status} runs={runs} roles={roles} invitations={invitations} projects={projects} />
  );
}
