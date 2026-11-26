import "server-only";

import { cookies } from "next/headers";

import { chosenSubject, subjectCookie, subjectsOf } from "./subjects";
import type { Project, Subject } from "./types";

/** El proyecto elegido en este tablero, si atiende varios y se eligió uno. */
export async function chosenIn(project: Pick<Project, "key" | "subjects">): Promise<Subject | null> {
  return chosenSubject(project, (await cookies()).get(subjectCookie(project.key))?.value);
}

/**
 * Cómo se llama aquello sobre lo que se trabaja en las pantallas que son de un proyecto a la vez (repositorio,
 * documentos): el elegido o el primero del tablero. Es el mismo que usa la API.
 */
export async function workNameOf(project: Pick<Project, "key" | "name" | "subjects">): Promise<string> {
  return ((await chosenIn(project)) ?? subjectsOf(project)[0])?.name ?? project.name;
}
