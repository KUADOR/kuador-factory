import "server-only";

import { redirect } from "next/navigation";

import { boardApi } from "@/lib/api";
import { chosenIn } from "@/lib/subject-server";

/** El repositorio es del proyecto: se configura en Organización → Proyectos (el elegido en el tablero, o su primero). */
export async function toProjectRepository(boardKey: string): Promise<never> {
  const project = await boardApi.project(boardKey.toUpperCase()).catch(() => null);
  const subject = project ? ((await chosenIn(project))?.key ?? project.subjects?.[0]?.key ?? project.key) : boardKey.toUpperCase();
  redirect(`/org/projects/${subject}/repository`);
}
