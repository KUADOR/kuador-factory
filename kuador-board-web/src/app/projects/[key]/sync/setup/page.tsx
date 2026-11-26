import { toProjectRepository } from "../redirect";

/** Las piezas y el vínculo pasaron al proyecto (Organización → Proyectos). */
export default async function RepositorySettingsPage({ params }: PageProps<"/projects/[key]/sync/setup">) {
  await toProjectRepository((await params).key);
}
