import type { Metadata } from "next";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/Section";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { SyncPanel } from "./SyncPanel";

export const dynamic = "force-dynamic";

type Props = { params: Promise<{ key: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  return { title: `Repositorio ${(await params).key.toUpperCase()}` };
}

export default async function SyncPage({ params }: Props) {
  const projectKey = (await params).key.toUpperCase();
  let project, link;
  try {
    [project, link] = await Promise.all([boardApi.project(projectKey), boardApi.repository(projectKey)]);
  } catch (e) {
    return failPage(e);
  }
  const where = link.linked ? (link.kind === "GITHUB" ? `github.com/${link.location} · rama ${link.branch}` : link.location) : null;
  const notReady = !link.linked ? "Vincula primero el repositorio" : !project.methodology ? "Elige primero la metodología" : null;
  if (notReady) {
    return (
      <div className="max-w-3xl px-6 py-6">
        <EmptyState title={notReady} action={<Button href={`/projects/${project.key}/settings`} tone="primary" size="sm">Ir a configuración</Button>}>
          La sincronización compara los documentos del tablero con los archivos del repositorio según las rutas de la metodología.
        </EmptyState>
      </div>
    );
  }
  let data;
  try {
    data = await Promise.all([boardApi.syncStatus(projectKey), boardApi.issues(projectKey)]);
  } catch (e) {
    return failPage(e);
  }
  const [status, issues] = data;
  return (
    <div className="flex flex-col gap-5 px-6 py-6">
      <p className="text-[13px] text-fg-muted">
        Vinculado a <code className="font-mono text-xs break-all text-fg">{where}</code>.{" "}
        {link.kind === "GITHUB"
          ? "Cada dev sigue trabajando con git como siempre: lo que el tablero exporta llega como Pull Request y lo que se fusiona en la rama principal se importa aquí."
          : "El tablero escribe los archivos pero no hace commits: revisa con git diff en la carpeta y haz commit tú."}
      </p>
      <SyncPanel projectKey={project.key} status={status} issues={issues} />
    </div>
  );
}
