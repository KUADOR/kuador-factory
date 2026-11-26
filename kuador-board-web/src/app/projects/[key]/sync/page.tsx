import { toProjectRepository } from "./redirect";

/** El repositorio pasó al proyecto (Organización → Proyectos). */
export default async function SyncPage({ params }: PageProps<"/projects/[key]/sync">) {
  await toProjectRepository((await params).key);
}
