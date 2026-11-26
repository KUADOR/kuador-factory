import type { Metadata } from "next";

import { BoardsView } from "@/components/org/BoardsView";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Tableros" };

export default async function OrgBoardsPage({ searchParams }: PageProps<"/org/boards">) {
  const params = await searchParams;
  let data;
  try {
    data = await Promise.all([
      boardApi.projects(),
      boardApi.teams(),
      boardApi.subjects(),
      boardApi.catalog("workflow-templates"),
      boardApi.workspacePackage().catch(() => undefined),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [boards, teams, subjects, templates, pkg] = data;
  return (
    <BoardsView
      boards={boards}
      teams={teams}
      subjects={subjects}
      templates={templates}
      recommended={pkg?.templates ?? []}
      initialOpen={typeof params.tablero === "string" ? params.tablero.toUpperCase() : null}
      creating={params.nuevo === "1"}
    />
  );
}
