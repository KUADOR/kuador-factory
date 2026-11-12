"use client";

import { Building2, ExternalLink, User } from "lucide-react";
import { useEffect, useRef, useState, useTransition } from "react";

import { githubDisconnectAction, githubManifestAction, githubStartInstallAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { Field, TextInput } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { GitHubConnection, GitHubOverview } from "@/lib/types";

const dateFormat = new Intl.DateTimeFormat("es-EC", { dateStyle: "medium" });

/** Formulario que el navegador envía a GitHub con el manifiesto de la app (así lo pide el flujo de GitHub). */
function ManifestPost({ form }: { form: { postUrl: string; manifest: string } | null }) {
  const ref = useRef<HTMLFormElement>(null);
  useEffect(() => {
    if (form) ref.current?.submit();
  }, [form]);
  if (!form) return null;
  return (
    <form ref={ref} action={form.postUrl} method="post" className="hidden">
      <input type="hidden" name="manifest" value={form.manifest} readOnly />
    </form>
  );
}

/**
 * GitHub del espacio: la app de la plataforma (se registra una vez) y las organizaciones donde está
 * instalada. Con una organización conectada, el tablero crea repos con sus bases y sincroniza sin tokens personales.
 */
export function GitHubConnections({ overview, workspaceName, done, error }: { overview: GitHubOverview; workspaceName: string; done: string | null; error: string | null }) {
  const [appName, setAppName] = useState(`Tablero ${workspaceName}`.slice(0, 34));
  const [organization, setOrganization] = useState("");
  const [manifest, setManifest] = useState<{ postUrl: string; manifest: string } | null>(null);
  const [removing, setRemoving] = useState<GitHubConnection | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  useEffect(() => {
    if (done === "app") notify.ok("App de GitHub registrada", "Ahora conecta la organización donde vivirán los repos.");
    else if (done) notify.ok(`Conectado a ${done}`);
  }, [done]);

  const install = () =>
    start(async () => {
      const r = await githubStartInstallAction();
      if (r.ok) window.location.assign(r.data.url);
      else notify.error("No se pudo empezar", r.error);
    });

  return (
    <div className="flex max-w-3xl flex-col gap-5 px-6 py-6">
      {error && (
        <p role="alert" className="rounded-md border border-danger/40 bg-danger/8 px-4 py-3 text-[13px] text-fg">
          {error}
        </p>
      )}

      <Section
        title="GitHub"
        description="Crea repos, protege ramas y sincroniza documentos con una app de GitHub. Sin tokens personales."
      >
        {!overview.app ? (
          overview.canRegister ? (
            <form
              className="flex flex-col gap-4"
              onSubmit={(e) => {
                e.preventDefault();
                start(async () => {
                  const r = await githubManifestAction(appName, organization.trim() || null);
                  if (r.ok) {
                    setFormError(null);
                    setManifest(r.data);
                  } else setFormError(r.error);
                });
              }}
            >
              <p className="text-[13px] leading-relaxed text-fg-muted">
                La plataforma todavía no tiene su app de GitHub. Se registra una sola vez: GitHub te muestra la app con los permisos justos (repos, contenido, Pull Requests y flujos de CI), la confirmas y su clave queda cifrada aquí.
              </p>
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Nombre de la app" hint="Único en GitHub. Lo ven las organizaciones que la instalan.">
                  {(id) => <TextInput id={id} required minLength={3} maxLength={34} value={appName} onChange={(e) => setAppName(e.target.value)} />}
                </Field>
                <Field label="Organización dueña" hint="Vacío = tu cuenta personal.">
                  {(id) => <TextInput id={id} value={organization} onChange={(e) => setOrganization(e.target.value)} placeholder="mi-organizacion" className="font-mono text-xs" />}
                </Field>
              </div>
              {formError && <p className="text-[13px] text-danger">{formError}</p>}
              <div className="flex justify-end">
                <Button type="submit" tone="primary" size="sm" icon={ExternalLink} loading={pending || !!manifest}>
                  Registrar la app en GitHub
                </Button>
              </div>
              <ManifestPost form={manifest} />
            </form>
          ) : (
            <p className="text-[13px] text-fg-muted">La plataforma todavía no tiene su app de GitHub. La registra una vez quien opera la plataforma.</p>
          )
        ) : (
          <div className="flex flex-col gap-4">
            <div className="flex flex-wrap items-center gap-2 text-[13px]">
              <span className="text-fg-muted">App</span>
              <a href={overview.app.htmlUrl} target="_blank" rel="noreferrer" className="k-link font-medium">
                {overview.app.name}
              </a>
              <span className="text-fg-faint">de {overview.app.ownerLogin}</span>
            </div>

            {overview.connections.length === 0 ? (
              <p className="text-[13px] text-fg-muted">Ninguna organización conectada todavía.</p>
            ) : (
              <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
                {overview.connections.map((c) => (
                  <li key={c.installationId} className="flex items-center gap-3 px-3 py-2.5 text-[13px]">
                    {c.accountType === "Organization" ? <Building2 className="size-4 text-fg-muted" aria-hidden /> : <User className="size-4 text-fg-muted" aria-hidden />}
                    <span className="min-w-0 flex-1">
                      <span className="font-medium text-fg-strong">{c.account}</span>
                      <span className="block text-xs text-fg-faint">
                        {c.accountType === "Organization" ? "Organización" : "Cuenta personal: vincula repos existentes, no crea repos nuevos"} · desde{" "}
                        {dateFormat.format(new Date(c.linkedAt))}
                      </span>
                    </span>
                    <a
                      href={c.accountType === "Organization" ? `https://github.com/organizations/${c.account}/settings/installations` : "https://github.com/settings/installations"}
                      target="_blank"
                      rel="noreferrer"
                      className="k-link text-xs"
                    >
                      Repos con acceso
                    </a>
                    <Button size="sm" tone="ghost" onClick={() => setRemoving(c)}>
                      Quitar
                    </Button>
                  </li>
                ))}
              </ul>
            )}
            <div className="flex justify-end">
              <Button tone="primary" size="sm" icon={ExternalLink} loading={pending} onClick={install}>
                Conectar una organización
              </Button>
            </div>
          </div>
        )}
      </Section>

      <ConfirmDialog
        open={removing != null}
        onCancel={() => setRemoving(null)}
        title={`¿Quitar ${removing?.account}?`}
        message="El espacio deja de usar esa organización. La app sigue instalada en GitHub: para quitarla del todo, desinstálala allá. Los proyectos vinculados con ella dejarán de sincronizar."
        confirmLabel="Quitar"
        pending={pending}
        onConfirm={() =>
          start(async () => {
            const r = removing ? await githubDisconnectAction(removing.installationId) : null;
            setRemoving(null);
            if (r && !r.ok) notify.error("No se pudo quitar", r.error);
            else notify.ok("Conexión quitada");
          })
        }
      />
    </div>
  );
}
