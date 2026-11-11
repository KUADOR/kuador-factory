"use client";

import { ExternalLink, GitBranchPlus, ShieldCheck, TriangleAlert } from "lucide-react";
import { useState, useTransition } from "react";

import { createRepositoryAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, SelectInput, Switch, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { GitHubConnection, NewRepositoryResult } from "@/lib/types";

/**
 * Proyecto nuevo con sus bases: el tablero crea el repo en una organización conectada, con la
 * metodología, AGENTS.md, CI mínimo, develop como rama de trabajo y main/develop protegidas.
 */
export function CreateRepository({ projectKey, projectName, connections }: { projectKey: string; projectName: string; connections: GitHubConnection[] }) {
  const orgs = connections.filter((c) => c.accountType === "Organization");
  const [installationId, setInstallationId] = useState<number | null>(orgs[0]?.installationId ?? null);
  const [name, setName] = useState(projectKey.toLowerCase());
  const [description, setDescription] = useState(projectName);
  const [privateRepo, setPrivateRepo] = useState(true);
  const [result, setResult] = useState<NewRepositoryResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const org = orgs.find((c) => c.installationId === installationId)?.account;

  if (result) {
    return (
      <div className="flex flex-col gap-3 text-[13px]">
        <p className="flex items-center gap-2 font-medium text-fg-strong">
          <GitBranchPlus className="size-4 text-ok" aria-hidden /> Repo creado y vinculado:{" "}
          <a href={result.url} target="_blank" rel="noreferrer" className="k-link inline-flex items-center gap-1 font-mono text-xs">
            {result.link.location} <ExternalLink className="size-3" aria-hidden />
          </a>
        </p>
        <ul className="flex flex-col gap-1 text-fg-muted">
          <li>· {result.documents} documento(s) de la metodología, AGENTS.md, README, .gitignore y CI mínimo en el primer commit.</li>
          <li>· Rama de trabajo: <code className="font-mono text-xs text-fg-strong">develop</code> (predeterminada). Lo publicado: <code className="font-mono text-xs text-fg-strong">main</code>.</li>
          {result.protectedBranches.length > 0 && (
            <li className="flex items-center gap-1.5">
              <ShieldCheck className="size-3.5 text-ok" aria-hidden /> Protegidas: {result.protectedBranches.join(" y ")} (solo por Pull Request con CI; main además con aprobación).
            </li>
          )}
        </ul>
        {result.warnings.map((w) => (
          <p key={w} className="flex gap-2 rounded-md border border-warn/40 bg-warn/8 px-3 py-2 text-fg">
            <TriangleAlert className="mt-0.5 size-4 shrink-0 text-warn" aria-hidden /> {w}
          </p>
        ))}
      </div>
    );
  }

  if (orgs.length === 0) {
    return (
      <p className="text-[13px] text-fg-muted">
        Para crear repos, conecta una organización de GitHub en <a href="/conexiones" className="k-link">Conexiones</a>.
      </p>
    );
  }

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault();
        if (installationId == null) return;
        start(async () => {
          const r = await createRepositoryAction(projectKey, { installationId, name: name.trim(), description: description.trim() || null, privateRepo });
          if (r.ok) {
            setError(null);
            setResult(r.data);
            notify.ok(`Repo ${r.data.link.location} creado`);
          } else setError(r.error);
        });
      }}
    >
      <div className="grid gap-3 sm:grid-cols-[12rem_minmax(0,1fr)]">
        <Field label="Organización">
          {(id) => (
            <SelectInput
              id={id}
              options={orgs.map((c) => ({ value: String(c.installationId), label: c.account }))}
              value={installationId == null ? null : String(installationId)}
              onChange={(v) => setInstallationId(v ? Number(v) : null)}
            />
          )}
        </Field>
        <Field label="Nombre del repo" hint={org ? `Quedará en ${org}/${name || "…"}` : undefined}>
          {(id) => <TextInput id={id} required maxLength={100} pattern="[A-Za-z0-9._\-]+" value={name} onChange={(e) => setName(e.target.value)} className="font-mono text-xs" />}
        </Field>
      </div>
      <Field label="Descripción">{(id) => <TextInput id={id} maxLength={350} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>
      <Switch checked={privateRepo} onChange={setPrivateRepo} label="Repositorio privado" />
      {privateRepo && (
        <p className="text-xs text-fg-muted">
          En el plan gratuito de GitHub, las ramas de repos privados no se pueden proteger; el tablero lo intentará y te avisará. Con GitHub Team sí quedan protegidas.
        </p>
      )}
      {error && (
        <p role="alert" className="text-[13px] text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end">
        <Button type="submit" tone="primary" size="sm" icon={GitBranchPlus} loading={pending}>
          Crear repositorio con sus bases
        </Button>
      </div>
    </form>
  );
}
