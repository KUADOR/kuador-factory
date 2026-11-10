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
      boardApi.accounts(),
      boardApi.agentTokens(),
      boardApi.agentsStatus(),
      boardApi.agentRuns({ limit: 100 }),
      boardApi.catalog("roles"),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [members, accounts, tokens, status, runs, roles] = data;
  return <TeamView members={members} accounts={accounts} tokens={tokens} status={status} runs={runs} roles={roles} />;
}
