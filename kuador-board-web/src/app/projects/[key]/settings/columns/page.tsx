import { redirect } from "next/navigation";

/** El flujo se cambia en un solo lugar: la pestaña Flujo. */
export default async function Page({ params }: { params: Promise<{ key: string }> }) {
  redirect(`/projects/${(await params).key}/flow`);
}
