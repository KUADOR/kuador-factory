"use client";

import { Check, Copy } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { clsx } from "clsx";
import { useState, useTransition } from "react";

import { NotificationSettingsForm } from "@/app/account/NotificationSettingsForm";
import { createIssueAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextArea, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { StepId } from "@/lib/onboarding";
import type { AgentKit, ComponentTemplate, GitHubOverview, Methodology, NotificationView, Project, RepositoryLink, RunnerToken } from "@/lib/types";

import { MethodologyPicker } from "../settings/MethodologyPicker";
import { RepositoryForm } from "../settings/RepositoryForm";
import { PiecesSetupForm } from "../settings/repositorio/Pieces";


const STEPS: Record<StepId, { title: string; description: string }> = {
  metodologia: { title: "Metodología", description: "Qué documentos pide el proyecto (spec, plan, tareas…) y qué escribe cada agente." },
  repositorio: { title: "Repositorio y piezas", description: "Dónde vive el código: el tablero crea los repos con sus bases, o vinculas uno que ya existe." },
  agentes: { title: "Tu cliente de IA", description: "Tus agentes trabajan en tu Claude Code (o Codex) con tu suscripción, conectados al tablero." },
  avisos: { title: "Notificaciones", description: "Te avisamos por correo o Telegram cuando algo te necesita: una pregunta, una aprobación o un reparto." },
  trabajo: { title: "Primer trabajo", description: "Una épica para arrancar: el PO la refina, tú apruebas y el arquitecto la reparte en tareas." },
};

const SKELETON = `Primera versión que funciona de punta a punta, mínima pero desplegable:

- Estructura de cada pieza con su build, pruebas y CI en verde.
- Un flujo completo de ejemplo que cruce todas las piezas (por ejemplo, la web llama a la API y la API a la base).
- Configuración por entorno y cómo levantarlo en local.`;

/** Asistente de inicio: un paso a la vez, con lo hecho marcado. Cada paso se puede saltar. */
export function Onboarding({
  project,
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
}: {
  project: Project;
  steps: StepId[];
  done: Record<StepId, boolean>;
  current: StepId | "fin";
  methodologies: Methodology[];
  link: RepositoryLink | null;
  github: GitHubOverview;
  templates: ComponentTemplate[];
  connections: RunnerToken[];
  kit: AgentKit;
  notifications: NotificationView | null;
  issueCount: number;
}) {
  const router = useRouter();
  const base = `/projects/${project.key}/inicio`;
  const index = current === "fin" ? steps.length : steps.indexOf(current);
  const next = steps[index + 1];
  const go = (step: StepId | "fin") => router.push(step === "fin" ? `${base}?paso=fin` : `${base}?paso=${step}`);
  const pending = steps.filter((s) => !done[s]).length;

  return (
    <div className="grid gap-6 px-6 py-6 lg:grid-cols-[15rem_minmax(0,1fr)]">
      <nav aria-label="Pasos para configurar el proyecto" className="flex flex-col gap-1">
        <p className="mb-2 text-xs text-fg-muted">{pending === 0 ? "Todo configurado." : `${steps.length - pending} de ${steps.length} pasos listos.`}</p>
        {steps.map((s, i) => (
          <Link
            key={s}
            href={`${base}?paso=${s}`}
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
            {STEPS[s].title}
          </Link>
        ))}
      </nav>

      <section className="flex max-w-3xl flex-col gap-4">
        {current === "fin" ? (
          <Finish project={project} issueCount={issueCount} pending={steps.filter((s) => !done[s])} />
        ) : (
          <>
            <header>
              <h2 className="text-base font-semibold text-fg-strong">
                {index + 1}. {STEPS[current].title}
              </h2>
              <p className="mt-0.5 text-[13px] text-fg-muted">{STEPS[current].description}</p>
            </header>
            <div className="rounded-lg border border-line bg-surface p-5">
              {current === "metodologia" && <MethodologyPicker project={project} methodologies={methodologies} />}
              {current === "repositorio" && link && <RepositoryStep project={project} link={link} github={github} templates={templates} />}
              {current === "agentes" && <AgentsStep connections={connections} kit={kit} onCheck={() => router.refresh()} />}
              {current === "avisos" &&
                (notifications ? <NotificationSettingsForm initial={notifications} /> : <p className="text-[13px] text-fg-muted">No se pudieron leer tus avisos.</p>)}
              {current === "trabajo" && <FirstWork project={project} existing={issueCount} onCreated={() => go(next ?? "fin")} />}
            </div>
            <div className="flex items-center justify-between">
              <Link href={`/projects/${project.key}`} className="k-link text-[13px]">
                Ir al tablero
              </Link>
              <Button tone={done[current] ? "primary" : "ghost"} onClick={() => go(next ?? "fin")}>
                {done[current] ? "Siguiente" : "Saltar por ahora"}
              </Button>
            </div>
          </>
        )}
      </section>
    </div>
  );
}

function RepositoryStep({ project, link, github, templates }: { project: Project; link: RepositoryLink; github: GitHubOverview; templates: ComponentTemplate[] }) {
  if (link.linked) {
    return (
      <p className="text-[13px] text-fg">
        Vinculado a <code className="font-mono text-xs">{link.location}</code>
        {link.branch ? ` · rama ${link.branch}` : ""}. Las piezas se ven y se suman en{" "}
        <Link href={`/projects/${project.key}/settings/repositorio`} className="k-link">
          Piezas y repositorio
        </Link>
        .
      </p>
    );
  }
  return (
    <div className="flex flex-col gap-6">
      {github.connections.length > 0 ? (
        <PiecesSetupForm projectKey={project.key} projectName={project.name} connections={github.connections} templates={templates} />
      ) : (
        <p className="text-[13px] text-fg-muted">
          Para que el tablero cree los repos, conecta primero tu organización de GitHub en{" "}
          <Link href="/conexiones" className="k-link">
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
          Ya tienes {connections.length === 1 ? "una conexión" : `${connections.length} conexiones`}: {connections.map((c) => c.name).join(", ")}. Si el repo lo creó el tablero, ya trae la conexión y el comando /pendientes.
        </p>
      ) : (
        <>
          <p className="text-[13px] text-fg-muted">En tu terminal, una sola vez:</p>
          <Command>{`claude mcp add --transport http --scope user ${server} ${url}`}</Command>
          <Command>{`claude mcp login ${server}`}</Command>
          <p className="text-xs text-fg-muted">
            Se abre el navegador para aprobar la conexión. ¿Usas Codex u otro cliente? Mira{" "}
            <Link href="/agentes-locales" className="k-link">
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
            notify.ok(`${r.data.key} creada en «${columnName}»`, "Tus agentes la atienden con /pendientes.");
            onCreated();
          } else setError(r.error);
        });
      }}
    >
      {existing > 0 && <p className="text-xs text-fg-muted">El proyecto ya tiene {existing} issue(s); puedes sumar otro o pasar al final.</p>}
      <Field label={epic ? "Épica" : "Título"}>{(id) => <TextInput id={id} required maxLength={255} value={title} onChange={(e) => setTitle(e.target.value)} />}</Field>
      <Field label="Qué se espera" hint="El PO la convierte en historia y criterios; lo que falte te lo pregunta.">
        {(id) => <TextArea id={id} rows={7} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <p className="text-xs text-fg-muted">
        Entra en «{columnName}»{project.stages.some((s) => s.column === column && s.roles.length > 0) ? ", la etapa de tus agentes: queda a tu cargo y ellos la toman con /pendientes" : ""}.
      </p>
      {error && (
        <p role="alert" className="text-[13px] text-danger">
          {error}
        </p>
      )}
      <div className="flex justify-end">
        <Button type="submit" tone="primary" loading={pending} disabled={title.trim().length === 0}>
          Crear {epic ? "épica" : "issue"}
        </Button>
      </div>
    </form>
  );
}

function Finish({ project, issueCount, pending }: { project: Project; issueCount: number; pending: StepId[] }) {
  return (
    <div className="flex flex-col gap-4 rounded-lg border border-line bg-surface p-6">
      <h2 className="text-base font-semibold text-fg-strong">{pending.length === 0 ? "El proyecto está listo" : "Casi listo"}</h2>
      {pending.length > 0 && (
        <p className="text-[13px] text-fg-muted">
          Quedaron sin hacer: {pending.map((s) => STEPS[s].title.charAt(0).toLowerCase() + STEPS[s].title.slice(1)).join(", ")}. Puedes volver a este asistente cuando quieras desde Configuración.
        </p>
      )}
      <ol className="flex list-decimal flex-col gap-1.5 pl-5 text-[13px] text-fg">
        {project.kind === "SOFTWARE" && project.methodology && (
          <li>
            Revisa la constitución y la arquitectura en{" "}
            <Link href={`/projects/${project.key}/docs`} className="k-link">
              Documentos
            </Link>
            : los agentes las siguen en cada etapa.
          </li>
        )}
        <li>
          En tu repo, abre Claude Code y escribe <code className="font-mono text-xs">/pendientes</code>
          {issueCount > 0 ? ": el PO empieza por tu primer trabajo." : "."}
        </li>
        <li>Te avisaremos cuando algo te necesite: una pregunta de un agente, una aprobación o un reparto de tareas.</li>
      </ol>
      <div>
        <Button href={`/projects/${project.key}`} tone="primary">
          Ir al tablero
        </Button>
      </div>
    </div>
  );
}
