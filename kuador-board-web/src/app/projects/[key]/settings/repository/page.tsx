import { toProjectRepository } from "../../sync/redirect";

/** El repositorio es del proyecto: se configura en Organización → Proyectos. */
export default async function Page({ params }: { params: Promise<{ key: string }> }) {
  await toProjectRepository((await params).key);
}
