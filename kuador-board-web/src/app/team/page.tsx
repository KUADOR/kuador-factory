import type { Metadata } from "next";

import { TeamView } from "@/components/team/TeamView";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Equipo" };

export default async function TeamPage() {
  let data;
  try {
    data = await Promise.all([
      boardApi.members(),
      // Correos y tokens solo los ve quien edita; para quien solo mira, la API responde 403.
      boardApi.accounts().catch(() => []),
      boardApi.agentTokens().catch(() => []),
      boardApi.agentsStatus(),
      boardApi.agentRuns({ limit: 100 }),
      boardApi.catalog("roles"),
      boardApi.invitations().catch(() => []),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [members, accounts, tokens, status, runs, roles, invitations] = data;
  return <TeamView members={members} accounts={accounts} tokens={tokens} status={status} runs={runs} roles={roles} invitations={invitations} />;
}
