"use client";

import Link from "next/link";
import { FolderGit2 } from "lucide-react";
import { useState, useTransition } from "react";

import { linkRepositoryAction, unlinkRepositoryAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { Field, TextInput, PasswordInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { RepositoryKind, RepositoryLink } from "@/lib/types";

export function RepositoryForm({ projectKey, link }: { projectKey: string; link: RepositoryLink }) {
  const [kind, setKind] = useState<RepositoryKind>(link.kind ?? "GITHUB");
  const [location, setLocation] = useState(link.location ?? "");
  const [branch, setBranch] = useState(link.branch ?? "");
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [pending, startTransition] = useTransition();
  const github = kind === "GITHUB";

  return (
    <div className="flex flex-col gap-4">
      {link.linked && (
        <div className="flex flex-wrap items-center gap-2 rounded-md border border-line bg-sunken px-3 py-2 text-xs">
          <FolderGit2 className="size-4 text-ok" aria-hidden />
          <span className="text-fg-muted">{link.kind === "GITHUB" ? "GitHub" : "Carpeta local"}</span>
          <code className="min-w-0 font-mono break-all text-fg-strong">
            {link.location}
            {link.branch ? `@${link.branch}` : ""}
          </code>
          <Link href={`/projects/${projectKey}/sync`} className="k-link ml-auto">
            Ver sincronización
          </Link>
        </div>
      )}

      <ChoiceCards
        name="repo-kind"
        label="Tipo de repositorio"
        columns={2}
        value={kind}
        onChange={setKind}
        choices={[
          { value: "GITHUB", label: "GitHub", hint: "Para equipos: lee la rama principal y exporta abriendo Pull Requests." },
          { value: "LOCAL", label: "Carpeta local", hint: "Solo para pruebas personales: escribe directo en tu disco." },
        ]}
      />

      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          startTransition(async () => {
            const r = await linkRepositoryAction(projectKey, { kind, location, branch: github ? branch.trim() || null : null, token: github ? token : null });
            if (r.ok) {
              setToken("");
              setBranch(r.data.branch ?? "");
              setError(null);
              notify.ok(github ? `Vinculado a ${r.data.location}` : "Carpeta vinculada", github ? `Rama ${r.data.branch}` : undefined);
            } else setError(r.error);
          });
        }}
      >
        <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_12rem]">
          <Field label={github ? "Repositorio" : "Carpeta"} hint={github ? "owner/repo" : "Ruta absoluta dentro de las carpetas permitidas."}>
            {(id) => (
              <TextInput
                id={id}
                required
                value={location}
                onChange={(e) => setLocation(e.target.value)}
                placeholder={github ? "empresa/proyecto-api" : "/Users/…/proyectos/proyecto-api"}
                className="font-mono text-xs"
              />
            )}
          </Field>
          {github && (
            <Field label="Rama principal" hint="Vacío = la rama por defecto.">
              {(id) => <TextInput id={id} value={branch} onChange={(e) => setBranch(e.target.value)} placeholder="main" className="font-mono text-xs" />}
            </Field>
          )}
        </div>
        {github && (
          <Field
            label="Token de GitHub"
            hint={
              <>
                Fine-grained token con acceso solo a este repositorio y permisos Contents y Pull requests (lectura y escritura). Se guarda cifrado y nunca se vuelve a mostrar.
              </>
            }
          >
            {(id) => (
              <PasswordInput
                id={id}
                required
                autoComplete="off"
                value={token}
                onChange={(e) => setToken(e.target.value)}
                placeholder={link.linked && link.kind === "GITHUB" ? "Token guardado (pega uno nuevo para cambiarlo)" : "github_pat_…"}
                className="font-mono text-xs"
              />
            )}
          </Field>
        )}
        {error && (
          <p role="alert" className="text-[13px] text-danger">
            {error}
          </p>
        )}
        <div className="flex flex-wrap items-center gap-2">
          {link.linked && (
            <Button tone="danger" size="sm" disabled={pending} onClick={() => setConfirming(true)}>
              Desvincular
            </Button>
          )}
          <Button type="submit" tone="primary" size="sm" loading={pending} className="ml-auto">
            {link.linked ? "Guardar vínculo" : "Vincular"}
          </Button>
        </div>
      </form>

      <ConfirmDialog
        open={confirming}
        onCancel={() => setConfirming(false)}
        title="¿Desvincular el repositorio?"
        message="Los documentos del tablero y los archivos del repositorio no se borran. Se olvida el estado de sincronización y los Pull Requests abiertos por el tablero."
        confirmLabel="Desvincular"
        pending={pending}
        onConfirm={() =>
          startTransition(async () => {
            const r = await unlinkRepositoryAction(projectKey);
            setConfirming(false);
            if (r.ok) {
              setLocation("");
              setBranch("");
              notify.ok("Repositorio desvinculado");
            } else setError(r.error);
          })
        }
      />
    </div>
  );
}
