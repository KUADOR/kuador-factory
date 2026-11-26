import type { Project, Subject } from "./types";

/**
 * Un tablero es un equipo y su flujo; un proyecto es aquello sobre lo que se trabaja (un sistema, una marca). Cuando el
 * tablero atiende varios, el elegido se recuerda en una cookie por tablero: filtra las tarjetas y dice de qué proyecto
 * son el repositorio, las piezas y los documentos que se ven.
 */
export const subjectCookie = (boardKey: string) => `kuador_proyecto_${boardKey.toUpperCase()}`;

/** Los proyectos del tablero (una API anterior a esta web no los trae: ninguno). */
export const subjectsOf = (project: Pick<Project, "subjects">): Subject[] => project.subjects ?? [];

/** Si hay que mostrar el proyecto (selector, etiqueta, filtro): solo cuando el tablero atiende más de uno. */
export const manySubjects = (project: Pick<Project, "subjects">) => subjectsOf(project).length > 1;

/** El elegido, si el tablero lo atiende; si no, ninguno (se ven todos). */
export function chosenSubject(project: Pick<Project, "subjects">, chosen: string | null | undefined): Subject | null {
  return subjectsOf(project).find((s) => s.key === chosen) ?? null;
}
