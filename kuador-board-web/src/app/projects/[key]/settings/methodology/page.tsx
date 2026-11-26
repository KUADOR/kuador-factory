import { redirect } from "next/navigation";

/** El manual de trabajo es del proyecto: se elige en Organización → Proyectos. */
export default async function Page({ params }: { params: Promise<{ key: string }> }) {
  redirect(`/projects/${(await params).key}/settings/general`);
}
