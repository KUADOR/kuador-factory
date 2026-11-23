import { redirect } from "next/navigation";

/** Los responsables y las aprobaciones se editan junto con los pasos, en una sola pantalla. */
export default async function StagesSettingsPage({ params }: PageProps<"/projects/[key]/settings/stages">) {
  redirect(`/projects/${(await params).key.toUpperCase()}/settings/columns#responsables`);
}
