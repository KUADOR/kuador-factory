import { redirect } from "next/navigation";

/** Todo lo del repositorio vive en la pestaña Repositorio. */
export default async function Page({ params }: { params: Promise<{ key: string }> }) {
  redirect(`/projects/${(await params).key}/sync/setup`);
}
