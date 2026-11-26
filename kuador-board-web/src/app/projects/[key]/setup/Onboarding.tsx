"use client";

import { Check, Copy, Sparkles } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { clsx } from "clsx";
import { useState, useTransition } from "react";

import { NotificationSettingsForm } from "@/app/account/NotificationSettingsForm";
import { assignIssueAction, createIssueAction } from "@/app/actions";
import { AiConnection } from "@/app/connections/AiConnection";
import { SelectInput } from "@/components/ui/Field";
import { Button } from "@/components/ui/Button";
import { Field, TextArea, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { StepId } from "@/lib/onboarding";
import type {
  AgentKit,
  ComponentTemplate,
  DraftPlan,
  PiecePlan,
  GitHubOverview,
  Methodology,
  NotificationView,
  Project,
  RepositoryLink,
  RunnerToken,
  TeamAgent,
  WorkspaceAiStatus,
} from "@/lib/types";

import { MethodologyPicker } from "../settings/MethodologyPicker";
import { RepositoryForm } from "../settings/RepositoryForm";
import { PiecesPlanForm, PiecesSetupForm } from "../settings/repository/Pieces";


const STEPS: Record<StepId, { title: string; description: string }> = {
  ai: { title: "IA de la empresa", description: "El proveedor y la clave con la que trabajan tus agentes. El consumo va a tu cuenta." },
  methodology: { title: "Manual de trabajo", description: "Qué documentos pide el proyecto y qué escribe cada agente." },
  pieces: { title: "Piezas", description: "De qué está hecho el proyecto: es la única fuente del stack." },
  docs: { title: "Documentos base", description: "Guían a los agentes desde el primer commit. Obligatorio antes del repo." },
  repository: { title: "Repositorio y piezas", description: "El tablero crea los repos con sus bases, o vinculas uno que ya existe." },
  client: { title: "Tu cliente de IA", description: "Tus agentes trabajan en tu Claude Code o Codex, conectados al tablero." },
  notifications: { title: "Notificaciones", description: "Te avisamos cuando algo te necesita." },
  firstWork: { title: "Primer trabajo", description: "Una épica para arrancar: el PO la refina, tú apruebas y el arquitecto la reparte." },
};

const SKELETON = `Primera versión que funciona de punta a punta, mínima pero desplegable:

- Estructura de cada pieza con su build, pruebas y CI en verde.
- Un flujo completo de ejemplo que cruce todas las piezas (por ejemplo, la web llama a la API y la API a la base).
- Configuración por entorno y cómo levantarlo en local.`;

/** Asistente de inicio: un paso a la vez, con lo hecho marcado. Cada paso se puede saltar, salvo los documentos base. */
export function Onboarding({
  project,
  workName,
  steps,
  done,
  current,
  methodologies,
  link,
  github,
  templates,
  connections,
  kit,
  notifications,
  issueCount,
  ai = null,
  boardAgents = [],
  existingProject = false,
  draft = null,
  plan = null,
}: {
  project: Project;
  /** Cómo se llama el proyecto en que se trabaja (el elegido, si el tablero atiende varios): da nombre a su repo. */
  workName?: string;
  steps: StepId[];
  done: Record<StepId, boolean>;
  current: StepId | "done";
  methodologies: Methodology[];
  link: RepositoryLink | null;
  github: GitHubOverview;
  templates: ComponentTemplate[];
  connections: RunnerToken[];
  kit: AgentKit;
  notifications: NotificationView | null;
  issueCount: number;
  ai?: WorkspaceAiStatus | null;
  boardAgents?: TeamAgent[];
  /** Se eligió «Ya existe» al crear el tablero: se vincula su repo y el agente lo lee. */
  existingProject?: boolean;
  /** Qué documentos base pide la metodología y cuáles faltan (software). */
  draft?: DraftPlan | null;
  /** Piezas planeadas antes del repo. */
  plan?: PiecePlan | null;
}) {
  const router = useRouter();
  const base = `/projects/${project.key}/setup`;
  const index = current === "done" ? steps.length : steps.indexOf(current);
  const next = steps[index + 1];
  const go = (step: StepId | "done") => router.push(step === "done" ? `${base}?step=fin` : `${base}?step=${step}`);
  const pending = steps.filter((s) => !done[s]).length;
  // Un proyecto nuevo no crea su repo sin sus documentos base; uno que ya existe se vincula y el agente los propone leyéndolo.
  // Orden de un proyecto nuevo: piezas (el stack) → documentos (con ese stack) → repo. Ninguno de los dos primeros se salta.
  const mandatory = ((current === "pieces" && !done.pieces) || (current === "docs" && !done.docs)) && !existingProject;
  const docsBlocked = current === "docs" && !done.pieces && !existingProject;
  const repoBlocked = current === "repository" && !done.docs && !existingProject && !link?.linked;

  return (
    <div className="grid gap-6 px-4 py-5 sm:px-6 sm:py-6 lg:grid-cols-[15rem_minmax(0,1fr)]">
      <nav aria-label="Pasos para configurar el tablero" className="flex flex-col gap-1">
        <p className="mb-2 text-xs text-fg-muted">{pending === 0 ? "Todo configurado." : `${steps.length - pending} de ${steps.length} pasos listos.`}</p>
        {steps.map((s, i) => (
          <Link
            key={s}
            href={`${base}?step=${s}`}
            aria-current={s === current ? "step" : undefined}
            className={clsx(
              "flex items-center gap-2.5 rounded-md px-2.5 py-2 text-[13px]",
              s === current ? "bg-hover text-fg-strong" : "text-fg-muted hover:bg-hover hover:text-fg-strong",
            )}
          >
            <span
              className={clsx(
                "tabular grid size-5 shrink-0 place-items-center rounded-full font-mono text-[10px]",
                done[s] ? "bg-ok/15 text-ok" : "border border-line-strong text-fg-muted",
              )}
            >
              {done[s] ? <Check className="size-3" aria-label="Listo" /> : i + 1}
            </span>
            {stepTitle(s, project)}
          </Link>
        ))}
      </nav>

      <section className="flex max-w-3xl flex-col gap-4">
        {current === "done" ? (
          <Finish project={project} issueCount={issueCount} pending={steps.filter((s) => !done[s])} />
        ) : (
          <>
            <header>
              <h2 className="text-base font-semibold text-fg-strong">
                {index + 1}. {stepTitle(current, project)}
              </h2>
              <p className="mt-0.5 text-[13px] text-fg-muted">{stepDescription(current, project)}</p>
            </header>
            <div className="rounded-lg border border-line bg-surface p-5">
              {current === "methodology" && <MethodologyPicker project={project} methodologies={methodologies} />}
              {current === "pieces" && (
                <PiecesStep project={project} templates={templates} plan={plan} linked={!!link?.linked} existing={existingProject} onSaved={() => go(next ?? "done")} />
              )}
              {docsBlocked && <Locked text="Primero elige las piezas: los documentos se redactan con su stack." step="pieces" label="Ir a Piezas" projectKey={project.key} />}
              {current === "docs" && !docsBlocked && <DocsStep project={project} draft={draft} />}
              {repoBlocked && <RepositoryLocked project={project} draft={draft} />}
              {current === "repository" && !repoBlocked && link && (
                <RepositoryStep project={project} workName={workName} link={link} github={github} templates={templates} existing={existingProject} plan={plan} />
              )}
              {current === "client" && <AgentsStep connections={connections} kit={kit} onCheck={() => router.refresh()} />}
              {current === "notifications" &&
                (notifications ? <NotificationSettingsForm initial={notifications} /> : <p className="text-[13px] text-fg-muted">No se pudieron leer tus avisos.</p>)}
              {current === "ai" && (ai ? <AiConnection status={ai} usage={null} /> : <p className="text-[13px] text-fg-muted">No se pudo leer la IA del espacio.</p>)}
              {current === "firstWork" &&
                (project.kind === "SOFTWARE" ? (
                  <FirstWork project={project} existing={issueCount} onCreated={() => go(next ?? "done")} />
                ) : (
                  <FirstRequest project={project} agents={boardAgents} aiReady={!!ai?.configured} />
                ))}
            </div>
            <div className="flex items-center justify-between">
              <Link href={`/projects/${project.key}`} className="k-link text-[13px]">
                Ir al tablero
              </Link>
              {mandatory ? (
                <span className="text-xs text-fg-muted">Obligatorio</span>
              ) : (
                <Button tone={done[current] ? "primary" : "ghost"} onClick={() => go(next ?? "done")}>
                  {done[current] ? "Siguiente" : "Saltar por ahora"}
                </Button>
              )}
            </div>
          </>
        )}
      </section>
    </div>
  );
}

function RepositoryStep({
  project,
  workName,
  link,
  github,
  templates,
  existing,
  plan,
}: {
  project: Project;
  workName?: string;
  link: RepositoryLink;
  github: GitHubOverview;
  templates: ComponentTemplate[];
  existing: boolean;
  plan: PiecePlan | null;
}) {
  if (link.linked) {
    return (
      <div className="flex flex-col gap-4">
        <p className="text-[13px] text-fg">
          Vinculado a <code className="font-mono text-xs">{link.location}</code>
          {link.branch ? ` · rama ${link.branch}` : ""}.{" "}
          <Link href={`/projects/${project.key}/sync/setup`} className="k-link">
            Piezas y repositorio
          </Link>
        </p>
        <RecognizeCta projectKey={project.key} />
      </div>
    );
  }
  if (existing) {
    // Un proyecto que ya existe: primero su repo (sin crear ni tocar nada); después el agente lo lee.
    return (
      <div className="flex flex-col gap-4">
        <p className="text-[13px] text-fg-muted">Indica el repo del proyecto. El tablero no crea ni sobrescribe nada.</p>
        <RepositoryForm projectKey={project.key} link={link} connections={github.connections} />
        {github.connections.length === 0 && (
          <p className="text-xs text-fg-faint">
            Sin la app de GitHub en{" "}
            <Link href="/connections?ver=github" className="k-link">
              Conexiones
            </Link>{" "}
            no llega el kit de agentes por PR.
          </p>
        )}
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-6">
      {github.connections.length > 0 ? (
        <PiecesSetupForm projectKey={project.key} projectName={workName ?? project.name} connections={github.connections} templates={templates} plan={plan} />
      ) : (
        <p className="text-[13px] text-fg-muted">
          Conecta primero tu organización de GitHub en{" "}
          <Link href="/connections?ver=github" className="k-link">
            Conexiones
          </Link>
          .
        </p>
      )}
      <details className="rounded-md border border-line">
        <summary className="cursor-pointer px-3.5 py-2.5 text-[13px] text-fg-strong">O vincular un repositorio que ya existe</summary>
        <div className="border-t border-line p-3.5">
          <RepositoryForm projectKey={project.key} link={link} connections={github.connections} />
        </div>
      </details>
    </div>
  );
}

/** Que el agente lea el proyecto que ya existe y arme el tablero (piezas, documentos, objetivo y tarjetas). */
/**
 * Documentos base: se redactan antes del repo porque van en su primer commit y guían a los agentes. Con metodología,
 * los suyos (en ODD, lo mínimo); sin metodología, el objetivo del tablero.
 */
function DocsStep({ project, draft }: { project: Project; draft: DraftPlan | null }) {
  if (!draft) return <p className="text-[13px] text-fg-muted">No se pudo leer qué documentos pide el proyecto.</p>;
  const base = draft.documents.filter((d) => d.suggested);
  const deployment = draft.documents.find((d) => d.kind === "DEPLOYMENT");
  const ready = draft.methodology ? draft.pendingBase.length === 0 : !!draft.goal?.trim();
  return (
    <div className="flex flex-col gap-5">
      {draft.methodology ? (
        <ul className="flex flex-col divide-y divide-line" aria-label="Documentos base">
          {base.map((d) => (
            <li key={d.kind} className="flex items-center gap-2.5 py-2 text-[13px] first:pt-0 last:pb-0">
              {d.written ? <Check className="size-3.5 shrink-0 text-ok" aria-hidden /> : <span aria-hidden className="size-3.5 shrink-0 rounded-full border border-line-strong" />}
              <span className={clsx("min-w-0 flex-1", d.written ? "text-fg-muted" : "text-fg-strong")}>{d.label}</span>
              <span className="sr-only">{d.written ? "redactado" : "pendiente"}</span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-[13px] text-fg">{draft.goal ? draft.goal : "Sin manual de trabajo basta con el objetivo del tablero."}</p>
      )}
      {deployment && (
        <p className="flex items-center gap-2.5 text-[13px]">
          {deployment.written ? <Check className="size-3.5 shrink-0 text-ok" aria-hidden /> : <span aria-hidden className="size-3.5 shrink-0 rounded-full border border-dashed border-line-strong" />}
          <span className="min-w-0 flex-1 text-fg">
            Despliegue <span className="text-fg-muted">· opcional, se actualiza con el proyecto</span>
          </span>
          <Link href={`/projects/${project.key}/docs/draft?doc=DEPLOYMENT&origen=asistente`} className="k-link text-xs">
            {deployment.written ? "Actualizar" : "Redactar"}
          </Link>
        </p>
      )}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <Button tone={ready ? "secondary" : "primary"} icon={Sparkles} href={`/projects/${project.key}/docs/draft?origen=asistente`}>
          {ready ? "Volver a redactar" : draft.methodology ? "Redactar con IA" : "Proponer el objetivo con IA"}
        </Button>
        <Link href={`/projects/${project.key}/setup?step=repository&origen=existente`} className="k-link text-xs">
          El proyecto ya existe
        </Link>
      </div>
    </div>
  );
}

/** Piezas: de qué está hecho el proyecto. Antes del repo se planean; después se ajustan en «Piezas y repositorio». */
function PiecesStep({
  project,
  templates,
  plan,
  linked,
  existing,
  onSaved,
}: {
  project: Project;
  templates: ComponentTemplate[];
  plan: PiecePlan | null;
  linked: boolean;
  existing: boolean;
  onSaved: () => void;
}) {
  if (linked || existing) {
    return (
      <p className="text-[13px] text-fg">
        {existing && !linked ? "Las propone el agente al leer el repo." : "Ya están en el repo."}{" "}
        <Link href={`/projects/${project.key}/${existing && !linked ? "setup?step=repository&origen=existente" : "sync/setup"}`} className="k-link">
          {existing && !linked ? "Vincular el repo" : "Piezas y repositorio"}
        </Link>
      </p>
    );
  }
  return <PiecesPlanForm projectKey={project.key} templates={templates} initial={plan} onSaved={onSaved} />;
}

function Locked({ text, step, label, projectKey }: { text: string; step: StepId; label: string; projectKey: string }) {
  return (
    <div className="flex flex-col gap-4 text-[13px]">
      <p className="text-fg">{text}</p>
      <Button tone="primary" href={`/projects/${projectKey}/setup?step=${step}`} className="self-start">
        {label}
      </Button>
    </div>
  );
}

/** El repo de un proyecto nuevo espera a sus documentos base (la API tampoco lo crea sin ellos). */
function RepositoryLocked({ project, draft }: { project: Project; draft: DraftPlan | null }) {
  const missing = draft?.methodology ? draft.pendingBase : [];
  return (
    <div className="flex flex-col gap-4 text-[13px]">
      <p className="text-fg">
        Falta {missing.length > 0 ? missing.join(", ") : "el objetivo del tablero"} antes de crear el repo.
      </p>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <Button tone="primary" href={`/projects/${project.key}/setup?step=docs`}>
          Ir a Documentos base
        </Button>
        <Link href={`/projects/${project.key}/setup?step=repository&origen=existente`} className="k-link text-xs">
          El proyecto ya existe
        </Link>
      </div>
    </div>
  );
}

function RecognizeCta({ projectKey }: { projectKey: string }) {
  return (
    <Link href={`/projects/${projectKey}/docs/draft?origen=existente`} className="k-link self-start text-[13px]">
      ¿El proyecto ya existía? Que el agente lo lea
    </Link>
  );
}

function Command({ children }: { children: string }) {
  return (
    <div className="relative">
      <pre className="overflow-x-auto rounded-md border border-line bg-sunken px-3 py-2.5 pr-10 font-mono text-[12px] text-fg-strong">{children}</pre>
      <span className="absolute top-1.5 right-1.5">
        <Button
          size="sm"
          tone="ghost"
          icon={Copy}
          iconOnly
          label="Copiar comando"
          onClick={async () => {
            await navigator.clipboard.writeText(children);
            notify.ok("Copiado");
          }}
        />
      </span>
    </div>
  );
}

function AgentsStep({ connections, kit, onCheck }: { connections: RunnerToken[]; kit: AgentKit; onCheck: () => void }) {
  const { server, url } = kit.connection;
  return (
    <div className="flex flex-col gap-4">
      {connections.length > 0 ? (
        <p className="text-[13px] text-fg">
          Ya tienes {connections.length === 1 ? "una conexión" : `${connections.length} conexiones`}: {connections.map((c) => c.name).join(", ")}.
        </p>
      ) : (
        <>
          <p className="text-[13px] text-fg-muted">En tu terminal, una sola vez:</p>
          <Command>{`claude mcp add --transport http --scope user ${server} ${url}`}</Command>
          <Command>{`claude mcp login ${server}`}</Command>
          <p className="text-xs text-fg-muted">
            ¿Codex u otro cliente? Mira{" "}
            <Link href="/local-agents" className="k-link">
              Agentes locales
            </Link>
            .
          </p>
        </>
      )}
      <div>
        <Button size="sm" tone="secondary" onClick={onCheck}>
          Comprobar conexión
        </Button>
      </div>
    </div>
  );
}

/** Columna donde arranca el trabajo: la primera etapa que cubren agentes (p. ej. Refinamiento); si no hay, la primera. */
function startColumn(project: Project): string {
  const stage = project.columns.map((c) => project.stages.find((s) => s.column === c.key)).find((s) => s && !s.gate && s.roles.length > 0);
  return stage?.column ?? project.columns[0].key;
}

/** En tableros que no son de software, el primer trabajo es un pedido a un agente que trabaja en el tablero. */
function stepTitle(step: StepId, project: Project): string {
  return step === "firstWork" && project.kind !== "SOFTWARE" ? "Primer pedido" : STEPS[step].title;
}

function stepDescription(step: StepId, project: Project): string {
  return step === "firstWork" && project.kind !== "SOFTWARE"
    ? "Pídele algo a un agente del tablero, con tus archivos."
    : STEPS[step].description;
}

/**
 * Primer pedido a un agente del tablero: la tarjeta, sus archivos y la asignación en un solo paso. Se asigna al final,
 * cuando los archivos ya están adjuntos, para que el agente los tenga al empezar.
 */
function FirstRequest({ project, agents, aiReady }: { project: Project; agents: TeamAgent[]; aiReady: boolean }) {
  const router = useRouter();
  // En Marketing el primer pedido es una pieza: entra en el Brief y sigue sola a Redacción y Diseño hasta tu revisión.
  const marketing = project.templateKey === "MARKETING";
  const first = (marketing && agents.find((a) => a.role === "MARKETING")) || agents.find((a) => a.role === "ANALYST") || agents[0];
  const [agentId, setAgentId] = useState<string | null>(first?.id ?? null);
  const [title, setTitle] = useState(
    first?.role === "MARKETING" ? "Post de LinkedIn para presentar lo que hacemos" : first?.role === "ANALYST" ? "Informe de ventas del último mes" : "",
  );
  const [description, setDescription] = useState(
    first?.role === "MARKETING"
      ? "Un post para LinkedIn que presente nuestro producto a quienes podrían comprarlo: qué problema resuelve, para quién y una llamada a pedir una demo. Con una imagen de 1200×627. Adjunto el logo y capturas del producto."
      : first?.role === "ANALYST"
        ? "Con el Excel adjunto, arma un informe en PDF de las ventas diarias del último mes: total del mes, promedio por día, los mejores y peores días, los productos que más se vendieron y una comparación con el mes anterior si los datos lo permiten."
        : "",
  );
  const [files, setFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  if (agents.length === 0) {
    return (
      <p className="text-[13px] text-fg-muted">
        Este tablero no tiene agentes.{" "}
        <Link href={project.team ? `/org/teams?equipo=${project.team.id}` : "/org/teams"} className="k-link">
          Súmalos a su equipo en Organización → Equipos
        </Link>
        .
      </p>
    );
  }

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault();
        if (!agentId) return;
        start(async () => {
          const created = await createIssueAction(project.key, {
            title: title.trim(),
            description: description.trim() || null,
            type: project.issueTypes.find((t) => t.type === "TASK")?.type ?? project.issueTypes[0].type,
            priority: "MEDIUM",
            storyPoints: null,
            assigneeId: null,
            parentKey: null,
            labels: [],
            status: marketing ? startColumn(project) : project.columns[0].key,
          });
          if (!created.ok) return setError(created.error);
          const key = created.data.key;
          for (const file of files) {
            const form = new FormData();
            form.append("file", file);
            const res = await fetch(`/attachments/${encodeURIComponent(key)}`, { method: "POST", body: form });
            if (!res.ok) {
              const problem = (await res.json().catch(() => null)) as { detail?: string } | null;
              return setError(`Se creó ${key}, pero no se pudo adjuntar ${file.name}: ${problem?.detail ?? `error ${res.status}`}. Adjúntalo desde la tarjeta y asígnasela.`);
            }
          }
          const assigned = await assignIssueAction(project.key, key, agentId);
          if (!assigned.ok) return setError(assigned.error);
          const name = agents.find((a) => a.id === agentId)?.name ?? "El agente";
          notify.ok(`${name} está trabajando en ${key}`);
          router.push(`/projects/${project.key}?issue=${key}`);
        });
      }}
    >
      {!aiReady && (
        <p role="status" className="text-[13px] text-warn">
          Falta la clave de IA de la empresa (paso 1): sin ella el agente no puede empezar.
        </p>
      )}
      <Field label="Para quién">
        {(id) => <SelectInput id={id} value={agentId} onChange={setAgentId} options={agents.map((a) => ({ value: a.id, label: `${a.name} · ${a.roleName}` }))} />}
      </Field>
      <Field label="Qué necesitas">{(id) => <TextInput id={id} required maxLength={255} value={title} onChange={(e) => setTitle(e.target.value)} />}</Field>
      <Field label="Detalle" hint="Si falta algo, el agente te pregunta.">
        {(id) => <TextArea id={id} rows={5} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <Field label="Archivos" hint="Excel, CSV, PDF o imágenes, hasta 25 MB cada uno.">
        {(id) => (
          <input
            id={id}
            type="file"
            multiple
            onChange={(e) => setFiles(Array.from(e.target.files ?? []))}
            className="block w-full text-[13px] text-fg file:mr-3 file:rounded-md file:border file:border-line-strong file:bg-surface file:px-3 file:py-1.5 file:text-[13px] file:text-fg-strong"
          />
        )}
      </Field>
      {error && (
        <p role="alert" className="text-[13px] text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end">
        <Button type="submit" tone="primary" loading={pending} disabled={!title.trim() || !agentId || !aiReady}>
          Enviar pedido
        </Button>
      </div>
    </form>
  );
}

function FirstWork({ project, existing, onCreated }: { project: Project; existing: number; onCreated: () => void }) {
  const epic = project.issueTypes.some((t) => t.type === "EPIC");
  const [title, setTitle] = useState(project.kind === "SOFTWARE" ? "Esqueleto del sistema" : "");
  const [description, setDescription] = useState(project.kind === "SOFTWARE" ? SKELETON : "");
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const column = startColumn(project);
  const columnName = project.columns.find((c) => c.key === column)?.name ?? column;

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault();
        start(async () => {
          const r = await createIssueAction(project.key, {
            title: title.trim(),
            description: description.trim() || null,
            type: epic ? "EPIC" : project.issueTypes[0].type,
            priority: "HIGH",
            storyPoints: null,
            assigneeId: null,
            parentKey: null,
            labels: [],
            status: column,
          });
          if (r.ok) {
            notify.ok(`${r.data.key} creada en «${columnName}»`);
            onCreated();
          } else setError(r.error);
        });
      }}
    >
      {existing > 0 && <p className="text-xs text-fg-muted">El tablero ya tiene {existing} tarjeta(s).</p>}
      <Field label={epic ? "Épica" : "Título"}>{(id) => <TextInput id={id} required maxLength={255} value={title} onChange={(e) => setTitle(e.target.value)} />}</Field>
      <Field label="Qué se espera">
        {(id) => <TextArea id={id} rows={7} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <p className="text-xs text-fg-muted">Entra en «{columnName}».</p>
      {error && (
        <p role="alert" className="text-[13px] text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end">
        <Button type="submit" tone="primary" loading={pending} disabled={title.trim().length === 0}>
          Crear {epic ? "épica" : "tarjeta"}
        </Button>
      </div>
    </form>
  );
}

function Finish({ project, issueCount, pending }: { project: Project; issueCount: number; pending: StepId[] }) {
  return (
    <div className="flex flex-col gap-4 rounded-lg border border-line bg-surface p-6">
      <h2 className="text-base font-semibold text-fg-strong">{pending.length === 0 ? "El tablero está listo" : "Casi listo"}</h2>
      {pending.length > 0 && (
        <p className="text-[13px] text-fg-muted">
          Quedaron sin hacer: {pending.map((s) => stepTitle(s, project).charAt(0).toLowerCase() + stepTitle(s, project).slice(1)).join(", ")}.
        </p>
      )}
      <ol className="flex list-decimal flex-col gap-1.5 pl-5 text-[13px] text-fg">
        {project.kind === "SOFTWARE" && project.methodology && (
          <li>
            Revisa la constitución y la arquitectura en{" "}
            <Link href={`/projects/${project.key}/docs`} className="k-link">
              Documentos
            </Link>
            .
          </li>
        )}
        {project.kind === "SOFTWARE" ? (
          <li>
            En tu repo, abre Claude Code y escribe <code className="font-mono text-xs">/pendientes</code>
            {issueCount > 0 ? ": el PO empieza por tu primer trabajo." : "."}
          </li>
        ) : (
          <li>Para pedirle algo a un agente, crea una tarjeta, adjunta lo que necesite y asígnasela.</li>
        )}
        <li>Te avisaremos cuando algo te necesite.</li>
      </ol>
      <div>
        <Button href={`/projects/${project.key}`} tone="primary">
          Ir al tablero
        </Button>
      </div>
    </div>
  );
}
