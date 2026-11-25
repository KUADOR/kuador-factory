import { redirect } from "next/navigation";

/** Personas y agentes del tablero están juntos en «Quién trabaja aquí». */
export default async function Page({ params }: { params: Promise<{ key: string }> }) {
  redirect(`/projects/${(await params).key}/settings/people#agentes`);
}
