"use client";

import { ExternalLink, GitBranchPlus, GitPullRequest, Plus, ShieldCheck, Trash, TriangleAlert } from "lucide-react";
import { useState, useTransition } from "react";

import { addPieceAction, setupPiecesAction } from "@/app/actions";
import { COMPONENT_KIND_LABEL } from "@/components/catalog/editors";
import { Button } from "@/components/ui/Button";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { Field, SelectInput, Switch, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { ComponentTemplate, GitHubConnection, PieceInput, ProjectPiece, SetupResult } from "@/lib/types";

type Layout = "MONOREPO" | "MULTI_REPO";
const PIECE_NAME = "[a-z][a-z0-9\\-]{1,39}";
const REPO_NAME = "[A-Za-z0-9._\\-]+";

/** Una pieza en edición: nombre, plantilla del catálogo, repo propio (si no va en carpeta) y variables de la plantilla. */
function PieceRow({
  piece,
  templates,
  ownRepo,
  onChange,
  onRemove,
}: {
  piece: PieceInput;
  templates: ComponentTemplate[];
  ownRepo: boolean;
  onChange: (p: PieceInput) => void;
  onRemove?: () => void;
}) {
  const template = templates.find((t) => t.key === piece.templateKey);
  return (
    <li className="flex flex-col gap-3 rounded-md border border-line bg-sunken p-3">
      <div className={ownRepo ? "grid gap-3 sm:grid-cols-[9rem_minmax(0,1fr)_minmax(0,1fr)_auto]" : "grid gap-3 sm:grid-cols-[9rem_minmax(0,1fr)_auto]"}>
        <Field label="Pieza" hint={ownRepo ? undefined : `Carpeta /${piece.name || "…"}`}>
          {(id) => (
            <TextInput
              id={id}
              size="sm"
              required
              pattern={PIECE_NAME}
              maxLength={40}
              value={piece.name}
              onChange={(e) => onChange({ ...piece, name: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, "-") })}
              placeholder="api"
              className="font-mono text-xs"
            />
          )}
        </Field>
        <Field label="Plantilla" hint={template ? `${template.stack}${template.templateRepo ? "" : " · sin código base todavía"}` : "Pieza libre: solo su README."}>
          {(id) => (
            <SelectInput
              id={id}
              size="sm"
              filter
              options={[{ value: "", label: "Sin plantilla" }, ...templates.map((t) => ({ value: t.key, label: `${t.name} · ${COMPONENT_KIND_LABEL[t.kind]}` }))]}
              value={piece.templateKey ?? ""}
              onChange={(v) => onChange({ ...piece, templateKey: v || null, variables: {} })}
            />
          )}
        </Field>
        {ownRepo && (
          <Field label="Repo de la pieza">
            {(id) => (
              <TextInput id={id} size="sm" required pattern={REPO_NAME} maxLength={100} value={piece.repoName ?? ""} onChange={(e) => onChange({ ...piece, repoName: e.target.value })} className="font-mono text-xs" />
            )}
          </Field>
        )}
        {onRemove && (
          <div className="flex items-end">
            <Button tone="ghost" size="sm" icon={Trash} iconOnly label={`Quitar ${piece.name || "pieza"}`} onClick={onRemove} />
          </div>
        )}
      </div>
      {template && template.variables.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2">
          {template.variables.map((v) => (
            <Field key={v.name} label={v.name} hint={v.description ?? undefined}>
              {(id) => (
                <TextInput
                  id={id}
                  size="sm"
                  maxLength={200}
                  value={piece.variables[v.name] ?? ""}
                  placeholder={v.defaultValue ?? ""}
                  onChange={(e) => onChange({ ...piece, variables: { ...piece.variables, [v.name]: e.target.value } })}
                  className="font-mono text-xs"
                />
              )}
            </Field>
          ))}
        </div>
      )}
    </li>
  );
}

/** Solo las variables que la persona escribió: las vacías toman el valor por defecto de la plantilla. */
function clean(p: PieceInput, ownRepo: boolean): PieceInput {
  const variables = Object.fromEntries(Object.entries(p.variables).filter(([, v]) => v.trim() !== ""));
  return { ...p, repoName: ownRepo ? (p.repoName?.trim() ?? null) : null, variables };
}

/** Resumen de lo que hizo el tablero en GitHub: repos, protecciones, Pull Requests y avisos. */
function SetupSummary({ result }: { result: SetupResult }) {
  return (
    <div className="flex flex-col gap-3 text-[13px]">
      {result.repositories.map((r) => (
        <div key={r.repository} className="flex flex-col gap-1.5">
          <p className="flex flex-wrap items-center gap-2 font-medium text-fg-strong">
            {r.pullRequestUrl ? <GitPullRequest className="size-4 text-info" aria-hidden /> : <GitBranchPlus className="size-4 text-ok" aria-hidden />}
            <a href={r.pullRequestUrl ?? r.url ?? "#"} target="_blank" rel="noreferrer" className="k-link inline-flex items-center gap-1 font-mono text-xs">
              {r.repository}
              {r.pullRequestUrl ? " · Pull Request" : ""} <ExternalLink className="size-3" aria-hidden />
            </a>
          </p>
          {r.protectedBranches.length > 0 && (
            <p className="flex items-center gap-1.5 text-xs text-fg-muted">
              <ShieldCheck className="size-3.5 text-ok" aria-hidden /> Protegidas: {r.protectedBranches.join(" y ")}
            </p>
          )}
          {r.warnings.map((w) => (
            <p key={w} className="flex gap-2 rounded-md border border-warn/40 bg-warn/8 px-3 py-2 text-xs text-fg">
              <TriangleAlert className="mt-0.5 size-3.5 shrink-0 text-warn" aria-hidden /> {w}
            </p>
          ))}
        </div>
      ))}
      {result.documents > 0 && <p className="text-xs text-fg-muted">{result.documents} documento(s) de la metodología quedaron en el repo del proyecto, sincronizados.</p>}
    </div>
  );
}

/** Armado inicial: repo del proyecto (bases y documentos) y sus piezas, en monorepo o un repo por pieza. */
export function PiecesSetupForm({
  projectKey,
  projectName,
  connections,
  templates,
}: {
  projectKey: string;
  projectName: string;
  connections: GitHubConnection[];
  templates: ComponentTemplate[];
}) {
  const orgs = connections.filter((c) => c.accountType === "Organization");
  const base = projectKey.toLowerCase();
  const [installationId, setInstallationId] = useState<number | null>(orgs[0]?.installationId ?? null);
  const [layout, setLayout] = useState<Layout>("MONOREPO");
  const [repoName, setRepoName] = useState(base);
  const [description, setDescription] = useState(projectName);
  const [privateRepo, setPrivateRepo] = useState(true);
  const [pieces, setPieces] = useState<PieceInput[]>([]);
  const [result, setResult] = useState<SetupResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const multi = layout === "MULTI_REPO";

  if (result) return <SetupSummary result={result} />;
  if (orgs.length === 0) {
    return (
      <p className="text-[13px] text-fg-muted">
        Para crear repos, conecta una organización de GitHub en <a href="/conexiones" className="k-link">Conexiones</a>.
      </p>
    );
  }

  const addPiece = () => {
    const name = ["api", "web", "app", "servicio"].find((n) => !pieces.some((p) => p.name === n)) ?? "";
    setPieces([...pieces, { name, templateKey: null, repoName: name ? `${base}-${name}` : "", variables: {} }]);
  };

  return (
    <form
      className="flex flex-col gap-5"
      onSubmit={(e) => {
        e.preventDefault();
        if (installationId == null) return;
        start(async () => {
          const r = await setupPiecesAction(projectKey, {
            installationId,
            repoName: repoName.trim(),
            description: description.trim() || null,
            privateRepo,
            pieces: pieces.map((p) => clean(p, multi)),
          });
          if (r.ok) {
            setError(null);
            setResult(r.data);
            notify.ok("Proyecto armado en GitHub");
          } else setError(r.error);
        });
      }}
    >
      <ChoiceCards
        name="pieces-layout"
        label="Organización de repos"
        columns={2}
        value={layout}
        onChange={setLayout}
        choices={[
          { value: "MONOREPO", label: "Monorepo", hint: "Un repo con cada pieza en su carpeta. Simple para un equipo y pocas piezas." },
          { value: "MULTI_REPO", label: "Un repo por pieza", hint: "Cada pieza en su repo (microservicios, microfrontends). Los documentos viven en el repo del proyecto." },
        ]}
      />
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
        <Field label={multi ? "Repo del proyecto (documentos)" : "Repo"} hint={`Quedará en ${orgs.find((c) => c.installationId === installationId)?.account ?? "…"}/${repoName || "…"}`}>
          {(id) => <TextInput id={id} required pattern={REPO_NAME} maxLength={100} value={repoName} onChange={(e) => setRepoName(e.target.value)} className="font-mono text-xs" />}
        </Field>
      </div>
      <Field label="Descripción">{(id) => <TextInput id={id} maxLength={350} value={description} onChange={(e) => setDescription(e.target.value)} />}</Field>

      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium text-fg-muted">Piezas</span>
          <Button size="sm" icon={Plus} onClick={addPiece}>
            Pieza
          </Button>
        </div>
        {pieces.length === 0 ? (
          <p className="text-xs text-fg-faint">Sin piezas: el proyecto nace vacío con sus bases y las piezas se suman después.</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {pieces.map((p, i) => (
              <PieceRow
                key={i}
                piece={p}
                templates={templates}
                ownRepo={multi}
                onChange={(next) => setPieces(pieces.map((x, j) => (j === i ? next : x)))}
                onRemove={() => setPieces(pieces.filter((_, j) => j !== i))}
              />
            ))}
          </ul>
        )}
      </div>

      <Switch checked={privateRepo} onChange={setPrivateRepo} label="Repos privados" />
      {privateRepo && <p className="-mt-3 text-xs text-fg-muted">En el plan gratuito de GitHub, las ramas de repos privados no se pueden proteger: el tablero lo intentará y te avisará.</p>}
      {error && (
        <p role="alert" className="text-[13px] text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end">
        <Button type="submit" tone="primary" size="sm" icon={GitBranchPlus} loading={pending}>
          Armar el proyecto en GitHub
        </Button>
      </div>
    </form>
  );
}

/** Piezas del proyecto y cómo sumar otra: carpeta (por Pull Request a develop) o repo propio. */
export function PiecesList({ projectKey, pieces, templates, mainRepo }: { projectKey: string; pieces: ProjectPiece[]; templates: ComponentTemplate[]; mainRepo: string | null }) {
  const [adding, setAdding] = useState<PieceInput | null>(null);
  const [ownRepo, setOwnRepo] = useState(pieces.some((p) => p.path === ""));
  const [privateRepo, setPrivateRepo] = useState(true);
  const [result, setResult] = useState<SetupResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  return (
    <div className="flex flex-col gap-4">
      {pieces.length === 0 ? (
        <p className="text-[13px] text-fg-muted">El proyecto todavía no tiene piezas.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
          {pieces.map((p) => (
            <li key={p.name} className="grid gap-1 px-3 py-2.5 text-[13px] sm:grid-cols-[8rem_minmax(0,1fr)_minmax(0,1fr)] sm:items-center sm:gap-3">
              <span className="font-mono font-medium text-fg-strong">{p.name}</span>
              <span className="min-w-0 truncate text-fg-muted">
                {COMPONENT_KIND_LABEL[p.kind]} · {p.stack}
              </span>
              <code className="min-w-0 truncate font-mono text-xs text-fg">
                {p.repository}
                {p.path ? `/${p.path}` : ""}
              </code>
            </li>
          ))}
        </ul>
      )}

      {result && <SetupSummary result={result} />}

      {adding ? (
        <form
          className="flex flex-col gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            start(async () => {
              const r = await addPieceAction(projectKey, clean(adding, ownRepo), privateRepo);
              if (r.ok) {
                setError(null);
                setAdding(null);
                setResult(r.data);
                notify.ok(`Pieza ${adding.name} agregada`);
              } else setError(r.error);
            });
          }}
        >
          <ChoiceCards
            name="piece-place"
            label="Dónde va la pieza"
            columns={2}
            value={ownRepo ? "OWN" : "FOLDER"}
            onChange={(v) => setOwnRepo(v === "OWN")}
            choices={[
              { value: "FOLDER", label: "Carpeta del repo del proyecto", hint: `Llega por Pull Request a develop de ${mainRepo ?? "el repo"}.` },
              { value: "OWN", label: "Repo propio", hint: "El tablero crea el repo con sus bases y ramas." },
            ]}
          />
          <ul>
            <PieceRow piece={adding} templates={templates} ownRepo={ownRepo} onChange={setAdding} />
          </ul>
          {ownRepo && <Switch checked={privateRepo} onChange={setPrivateRepo} label="Repo privado" />}
          {error && <p className="text-[13px] text-danger">{error}</p>}
          <div className="flex justify-end gap-2">
            <Button tone="ghost" size="sm" onClick={() => setAdding(null)}>
              Cancelar
            </Button>
            <Button type="submit" tone="primary" size="sm" loading={pending}>
              Agregar pieza
            </Button>
          </div>
        </form>
      ) : (
        <div className="flex justify-end">
          <Button size="sm" icon={Plus} onClick={() => setAdding({ name: "", templateKey: null, repoName: "", variables: {} })}>
            Agregar pieza
          </Button>
        </div>
      )}
    </div>
  );
}
