import { RepoNav } from "./RepoNav";

/** Todo lo del repositorio del tablero en una pestaña: sincronizar documentos y kit, y sus piezas y su vínculo. */
export default async function RepositoryLayout({ params, children }: LayoutProps<"/projects/[key]/sync">) {
  const projectKey = (await params).key.toUpperCase();
  return (
    <div className="flex flex-col">
      <div className="px-4 pt-4 sm:px-6">
        <RepoNav projectKey={projectKey} />
      </div>
      <div className="min-w-0">{children}</div>
    </div>
  );
}
