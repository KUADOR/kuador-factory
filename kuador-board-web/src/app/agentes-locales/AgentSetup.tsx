"use client";

import { Copy, Download } from "lucide-react";
import { clsx } from "clsx";
import { useState, useTransition } from "react";

import { agentKitAction, createRunnerTokenAction, revokeRunnerTokenAction } from "@/app/actions";
import { TokenManager } from "@/components/tokens/TokenManager";
import { Button } from "@/components/ui/Button";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { Switch } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { AgentClient, AgentKit, McpServer, RunnerToken } from "@/lib/types";

const CLIENTS: { value: AgentClient; label: string; hint: string }[] = [
  { value: "claude-code", label: "Claude Code", hint: "Un subagente por rol y el comando /pendientes." },
  { value: "codex", label: "Codex", hint: ".codex/config.toml y AGENTS.md con el flujo de trabajo." },
  { value: "mcp", label: "Otro cliente MCP", hint: "Cursor, OpenCode, Cline… La conexión y un AGENTS.md para adaptar." },
];

/** Bloque de comando o archivo con botón de copiar. */
function Code({ children, label }: { children: string; label: string }) {
  return (
    <div className="relative">
      <pre className="overflow-x-auto rounded-md border border-line bg-sunken px-3 py-2.5 pr-10 font-mono text-[12px] leading-relaxed text-fg-strong">{children}</pre>
      <span className="absolute top-1.5 right-1.5">
        <Button
          size="sm"
          tone="ghost"
          icon={Copy}
          iconOnly
          label={`Copiar ${label}`}
          onClick={async () => {
            await navigator.clipboard.writeText(children);
            notify.ok("Copiado");
          }}
        />
      </span>
    </div>
  );
}

function Step({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <li className="grid grid-cols-[1.5rem_minmax(0,1fr)] gap-3">
      <span className="tabular grid size-6 place-items-center rounded-full border border-line-strong font-mono text-[11px] text-fg-muted">{n}</span>
      <div className="flex min-w-0 flex-col gap-2">
        <p className="text-[13px] font-medium text-fg-strong">{title}</p>
        {children}
      </div>
    </li>
  );
}

const muted = "text-[13px] leading-relaxed text-fg-muted";
const mono = "font-mono text-[12px]";

/** Cómo se trabaja en cada cliente, una vez puesto el kit. */
function Usage({ client, kit }: { client: AgentClient; kit: AgentKit }) {
  const { server, tokenVar } = kit.connection;
  if (client === "claude-code") {
    return (
      <>
        <Step n={1} title="Usa tu suscripción, no una API key">
          <p className={muted}>Si tienes ANTHROPIC_API_KEY definida, Claude Code cobra cada uso a esa clave en vez de a tu plan.</p>
          <Code label="comando">unset ANTHROPIC_API_KEY</Code>
        </Step>
        <Step n={2} title="Abre Claude Code en el repo y pide tus pendientes">
          <Code label="comando">{"claude\n/pendientes"}</Code>
          <p className={muted}>
            Filtra con <code className={mono}>/pendientes MED</code> (un proyecto) o <code className={mono}>/pendientes QA</code> (un rol). La primera vez, aprueba el servidor «{server}» del .mcp.json.
          </p>
        </Step>
      </>
    );
  }
  if (client === "codex") {
    return (
      <>
        <Step n={1} title="Marca el repo como de confianza">
          <p className={muted}>Codex solo lee el .codex/config.toml de proyectos de confianza. Si ya tenías ese archivo o un AGENTS.md, agrega lo del kit en vez de reemplazarlos.</p>
        </Step>
        <Step n={2} title="Abre Codex en el repo y pide tus pendientes">
          <Code label="comando">{"codex\n> Trabaja mis pendientes del tablero"}</Code>
          <p className={muted}>AGENTS.md le explica el flujo: toma cada issue con el rol que pide y sigue el manual de ese rol desde el tablero.</p>
        </Step>
      </>
    );
  }
  return (
    <>
      <Step n={1} title="Agrega el servidor en tu cliente">
        <p className={muted}>
          Es un servidor MCP por HTTP: URL <code className={mono}>{kit.connection.url}</code> y cabecera <code className={mono}>Authorization: Bearer</code> con el valor de{" "}
          <code className={mono}>{tokenVar}</code>. mcp-servidores.json trae el formato más común; cada cliente lo pide en su propio archivo.
        </p>
      </Step>
      <Step n={2} title="Dale las instrucciones del flujo">
        <p className={muted}>Muchos clientes leen AGENTS.md solos; si el tuyo no, pega su contenido en sus reglas o instrucciones. Después pídele «trabaja mis pendientes del tablero».</p>
      </Step>
    </>
  );
}

/**
 * Conectar el cliente de IA de una persona con el tablero: su conexión (token), el kit para el repo en
 * el formato de su cliente y cómo trabajar los pendientes.
 */
export function AgentSetup({
  tokens,
  servers,
  initialKit,
  initialMcp,
  boardUrl,
  personName,
}: {
  tokens: RunnerToken[];
  servers: McpServer[];
  initialKit: AgentKit;
  initialMcp: string[];
  boardUrl: string;
  personName: string;
}) {
  const [client, setClient] = useState<AgentClient>("claude-code");
  const [mcp, setMcp] = useState(initialMcp);
  const [kit, setKit] = useState(initialKit);
  const [open, setOpen] = useState(initialKit.files[0]?.path ?? "");
  const [pending, start] = useTransition();
  const { tokenVar, server } = kit.connection;
  const query = new URLSearchParams([["client", client], ...mcp.map((k) => ["mcp", k])]).toString();
  const shown = kit.files.find((f) => f.path === open) ?? kit.files[0];

  const reload = (nextClient: AgentClient, nextMcp: string[]) =>
    start(async () => {
      const r = await agentKitAction(nextClient, nextMcp);
      if (r.ok) {
        setKit(r.data);
        setOpen((current) => (r.data.files.some((f) => f.path === current) ? current : (r.data.files[0]?.path ?? "")));
      } else notify.error("No se pudo actualizar el kit", r.error);
    });

  return (
    <div className="flex max-w-3xl flex-col gap-5 px-6 py-6">
      <Section
        title="1 · Tu conexión"
        description={`Tu cliente de IA entra al tablero como los agentes del equipo, en nombre de ${personName}. Solo abre el servidor MCP del tablero: no sirve para entrar a la web ni a la API. Crea una por equipo.`}
      >
        <TokenManager
          tokens={tokens}
          create={createRunnerTokenAction}
          revoke={revokeRunnerTokenAction}
          copy={{
            empty: "Aún no conectaste ningún equipo.",
            namePlaceholder: "Nombre, ej. Laptop con Codex",
            create: "Crear conexión",
            revokeTitle: (name) => `¿Desconectar «${name}»?`,
            revokeMessage: "Ese equipo dejará de tener acceso al tablero de inmediato.",
            revoked: "Conexión revocada",
          }}
          onIssued={(secret) => (
            <div className="mt-3 flex flex-col gap-1.5">
              <p className="text-fg">Guárdalo en tu shell (por ejemplo en ~/.zshrc), nunca en el repositorio:</p>
              <Code label="comando">{`export ${tokenVar}=${secret}`}</Code>
            </div>
          )}
        />
      </Section>

      <Section title="2 · Kit para el repositorio" description={`Archivos para la raíz del repo. El servidor se llama «${server}», como este espacio. No llevan secretos: se pueden versionar.`}>
        <div className="flex flex-col gap-4">
          <ChoiceCards
            name="agent-client"
            label="Cliente de IA"
            value={client}
            onChange={(next) => {
              setClient(next);
              reload(next, mcp);
            }}
            choices={CLIENTS}
          />

          <div className="flex flex-col gap-2">
            <p className="text-xs font-medium text-fg-muted">Servidores MCP del catálogo que se suman al del tablero</p>
            <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
              {servers.map((s) => (
                <li key={s.key} className="flex items-start gap-3 px-3 py-2.5">
                  <div className="min-w-0 flex-1">
                    <p className="text-[13px] font-medium text-fg-strong">{s.name}</p>
                    <p className="text-xs text-fg-muted">{s.description}</p>
                    {s.envVars.length > 0 && (
                      <p className="mt-1 font-mono text-[11px] text-fg-faint">{s.envVars.map((v) => v.name + (v.required ? "" : " (opcional)")).join(" · ")}</p>
                    )}
                  </div>
                  <Switch
                    checked={mcp.includes(s.key)}
                    hideLabel
                    label={`Incluir ${s.name}`}
                    disabled={pending}
                    onChange={(on) => {
                      const next = on ? [...mcp, s.key] : mcp.filter((k) => k !== s.key);
                      setMcp(next);
                      reload(client, next);
                    }}
                  />
                </li>
              ))}
            </ul>
          </div>

          <div className="overflow-hidden rounded-md border border-line">
            <div role="tablist" aria-label="Archivos del kit" className="flex gap-1 overflow-x-auto border-b border-line bg-sunken px-2 py-1.5">
              {kit.files.map((f) => (
                <button
                  key={f.path}
                  type="button"
                  role="tab"
                  aria-selected={f.path === shown?.path}
                  onClick={() => setOpen(f.path)}
                  className={clsx(
                    "shrink-0 rounded px-2 py-1 font-mono text-[11px] whitespace-nowrap",
                    f.path === shown?.path ? "bg-hover text-fg-strong" : "text-fg-muted hover:text-fg-strong",
                  )}
                >
                  {f.path}
                </button>
              ))}
            </div>
            {shown && <pre className={clsx("max-h-96 overflow-auto px-4 py-3 font-mono text-[12px] leading-relaxed text-fg", pending && "opacity-60")}>{shown.content}</pre>}
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <Button tone="primary" size="sm" icon={Download} href={`/agentes-locales/kit?${query}`} download>
              Descargar kit (.zip)
            </Button>
            <span className="text-xs text-fg-muted">{kit.files.length} archivos para {CLIENTS.find((c) => c.value === client)?.label}</span>
          </div>
          <div className="flex flex-col gap-1.5">
            <p className="text-xs text-fg-muted">O desde la raíz del repo, con tu conexión. No reemplaza archivos que ya existan:</p>
            <Code label="comando">{`curl -fsSL -H "Authorization: Bearer $${tokenVar}" "${boardUrl}/api/agent-kit.zip?${query}" -o kit-agentes.zip && unzip -n kit-agentes.zip && rm kit-agentes.zip`}</Code>
          </div>
        </div>
      </Section>

      <Section title="3 · Trabaja tus pendientes">
        <ol className="flex flex-col gap-5">
          <Usage client={client} kit={kit} />
          <Step n={3} title="Qué atiende">
            <p className={muted}>
              Los issues que supervisas: los que asignaste a un agente o llevaste a una etapa que cubren agentes (por ejemplo, al aprobar una puerta). Cada issue lo trabaja el rol que lo cubre, firmado por ese agente, y termina entregado, devuelto o con una pregunta para ti. Las puertas siguen siendo de las personas. El historial muestra desde qué cliente se trabajó. Todo cuenta para los límites de tu cuenta en ese cliente.
            </p>
          </Step>
        </ol>
      </Section>
    </div>
  );
}
