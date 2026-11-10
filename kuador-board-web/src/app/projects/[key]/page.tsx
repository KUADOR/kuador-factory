import type { Metadata } from "next";

import { Board } from "@/components/board/Board";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";

type Props = { params: Promise<{ key: string }>; searchParams: Promise<{ issue?: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { key } = await params;
  return { title: `Tablero ${key.toUpperCase()}` };
}

export default async function BoardPage({ params, searchParams }: Props) {
  const { key } = await params;
  const { issue } = await searchParams;
  const projectKey = key.toUpperCase();
  let data;
  try {
    data = await Promise.all([
      boardApi.project(projectKey),
      boardApi.issues(projectKey),
      boardApi.members(),
      boardApi.agentRuns({ limit: 50 }).catch(() => []),
      boardApi.me(),
      boardApi.catalog("roles"),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [project, issues, members, runs, me, roles] = data;
  return <Board project={project} issues={issues} members={members} runs={runs} me={me} roles={roles} initialOpenKey={issue?.toUpperCase()} />;
}
