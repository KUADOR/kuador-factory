import { redirect } from "next/navigation";

/** El manual de trabajo se elige en «General». */
export default async function Page({ params }: { params: Promise<{ key: string }> }) {
  redirect(`/projects/${(await params).key}/settings/general#manual`);
}
