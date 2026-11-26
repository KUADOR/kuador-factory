import { redirect } from "next/navigation";

/** Las personas y los agentes son del equipo del tablero: se arman en Organización → Equipos. */
export default async function Page({ params }: { params: Promise<{ key: string }> }) {
  redirect(`/projects/${(await params).key}/settings/general`);
}
