import { redirect } from "next/navigation";

export default async function ProjectSettingsIndex({ params }: PageProps<"/projects/[key]/settings">) {
  redirect(`/projects/${(await params).key.toUpperCase()}/settings/general`);
}
