import { redirect } from "next/navigation";

/** Correo y rutinas están juntos en «Entradas». */
export default async function Page({ params }: { params: Promise<{ key: string }> }) {
  redirect(`/projects/${(await params).key}/settings/intake#rutinas`);
}
