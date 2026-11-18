import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireEditor } from "@/lib/require-editor";
import type { GitHubOverview, NotificationView } from "@/lib/types";

import { setupDone, setupSteps, type StepId } from "@/lib/onboarding";

import { Onboarding } from "./Onboarding";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Configurar proyecto" };

const NO_GITHUB: GitHubOverview = { app: null, canRegister: false, connections: [] };

/**
 * Asistente de inicio de un proyecto: los pasos que lo dejan listo para trabajar con personas y agentes. Cada paso usa
 * la misma configuración que sus pestañas; se puede saltar y volver cuando sea.
 */
export default async function ProjectOnboardingPage({ params, searchParams }: PageProps<"/projects/[key]/setup">) {
  const key = (await params).key.toUpperCase();
  await requireEditor(`/projects/${key}`);
  const { step, origen } = await searchParams;
  let data;
  try {
    const project = await boardApi.project(key);
    const software = project.kind === "SOFTWARE";
    data = await Promise.all([
      project,
      software ? boardApi.catalog("methodologies") : [],
      software ? boardApi.repository(key) : null,
      software ? boardApi.github().catch(() => NO_GITHUB) : NO_GITHUB,
      software ? boardApi.catalog("component-templates") : [],
      boardApi.runnerTokens(),
      boardApi.agentKit("claude-code", []),
      boardApi.notifications().catch((): NotificationView | null => null),
      boardApi.issues(key),
      software ? null : boardApi.workspaceAi().catch(() => null),
      software ? [] : boardApi.boardTeam(key).catch(() => []),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [project, methodologies, link, github, templates, connections, kit, notifications, issues, ai, team] = data;
  const done = setupDone(project, link, connections, notifications, issues.length, !!ai?.configured);
  const steps = setupSteps(project);
  const requested = typeof step === "string" && steps.includes(step as StepId) ? (step as StepId) : null;
  return (
    <Onboarding
      project={project}
      steps={steps}
      done={done}
      current={step === "done" ? "done" : (requested ?? steps.find((s) => !done[s]) ?? "done")}
      methodologies={methodologies}
      link={link}
      github={github}
      templates={templates}
      connections={connections}
      kit={kit}
      notifications={notifications}
      issueCount={issues.length}
      ai={ai}
      boardAgents={team.filter((a) => a.inTeam && a.mode === "SERVER")}
      existingProject={origen === "existente"}
    />
  );
}
