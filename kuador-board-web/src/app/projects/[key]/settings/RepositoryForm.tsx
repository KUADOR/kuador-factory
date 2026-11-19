"use client";

import Link from "next/link";
import { FolderGit2 } from "lucide-react";
import { useState, useTransition } from "react";

import { linkRepositoryAction, unlinkRepositoryAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { Field, PasswordInput, SelectInput, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { GitHubConnection, RepositoryKind, RepositoryLink } from "@/lib/types";

const KIND_LABEL: Record<RepositoryKind, string> = { GITHUB_APP: "GitHub (app)", GITHUB: "GitHub con token" };

export function RepositoryForm({ projectKey, link, connections }: { projectKey: string; link: RepositoryLink; connections: GitHubConnection[] }) {
  const [kind, setKind] = useState<RepositoryKind>(link.kind ?? (connections.length > 0 ? "GITHUB_APP" : "GITHUB"));
  const [installationId, setInstallationId] = useState<number | null>(link.installationId ?? connections[0]?.installationId ?? null);
  const [location, setLocation] = useState(link.location ?? "");
  const [branch, setBranch] = useState(link.branch ?? "");
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [pending, startTransition] = useTransition();
  const github = kind === "GITHUB";
  const viaApp = kind === "GITHUB_APP";

  return (
    <div className="flex flex-col gap-4">
      {link.linked && (
        <div className="flex flex-wrap items-center gap-2 rounded-md border border-line bg-sunken px-3 py-2 text-xs">
          <FolderGit2 className="size-4 text-ok" aria-hidden />
          <span className="text-fg-muted">{link.kind ? KIND_LABEL[link.kind] : ""}</span>
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
          ...(connections.length > 0
            ? [{ value: "GITHUB_APP" as const, label: KIND_LABEL.GITHUB_APP, hint: "Con la conexión de GitHub del espacio." }]
            : []),
          { value: "GITHUB", label: KIND_LABEL.GITHUB, hint: "Con un token tuyo." },
        ]}
      />

      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          startTransition(async () => {
            const r = await linkRepositoryAction(projectKey, {
              kind,
              location,
              branch: branch.trim() || null,
              token: github ? token : null,
              installationId: viaApp ? installationId : null,
            });
            if (r.ok) {
              setToken("");
              setBranch(r.data.branch ?? "");
              setError(null);
              notify.ok(`Vinculado a ${r.data.location}`, `Rama ${r.data.branch}`);
            } else setError(r.error);
          });
        }}
      >
        {viaApp && (
          <Field label="Conexión de GitHub">
            {(id) => (
              <SelectInput
                id={id}
                options={connections.map((c) => ({ value: String(c.installationId), label: c.account }))}
                value={installationId == null ? null : String(installationId)}
                onChange={(v) => setInstallationId(v ? Number(v) : null)}
              />
            )}
          </Field>
        )}
        <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_12rem]">
          <Field label="Repositorio" hint="owner/repo">
            {(id) => (
              <TextInput
                id={id}
                required
                value={location}
                onChange={(e) => setLocation(e.target.value)}
                placeholder={`${connections.find((c) => c.installationId === installationId)?.account ?? "empresa"}/proyecto-api`}
                className="font-mono text-xs"
              />
            )}
          </Field>
          <Field label="Rama principal" hint="Vacío = la rama por defecto.">
            {(id) => <TextInput id={id} value={branch} onChange={(e) => setBranch(e.target.value)} placeholder="main" className="font-mono text-xs" />}
          </Field>
        </div>
        {github && (
          <Field
            label="Token de GitHub"
            hint="Fine-grained, solo este repo, Contents y Pull requests de escritura. No se vuelve a mostrar."
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
