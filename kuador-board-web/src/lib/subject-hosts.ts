import type { Project, SubjectListing } from "./types";

/**
 * Por dónde se atiende lo de un proyecto: los tableros que lo trabajan con repositorio (código) y con los documentos del
 * proyecto (manual y documentos base). Sin ninguno, esa parte del proyecto todavía no tiene uso.
 */
export function subjectHosts(subject: SubjectListing, boards: Project[]): { code: Project[]; documents: Project[] } {
  const mine = boards.filter((b) => subject.boards.includes(b.key));
  return {
    code: mine.filter((b) => b.capabilities.includes("REPOSITORY")),
    documents: mine.filter((b) => b.capabilities.includes("PROJECT_DOCS")),
  };
}
