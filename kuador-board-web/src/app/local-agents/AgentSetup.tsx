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
  { value: "claude-code", label: "Claude Code", hint: "Subagentes por rol y /pendientes." },
  { value: "codex", label: "Codex", hint: "Sigue el AGENTS.md del repo." },
  { value: "mcp", label: "Otro cliente MCP", hint: "Cursor, OpenCode, Cline…" },
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

/** Cómo conectar cada cliente: un comando y un inicio de sesión en el navegador. */
function Connect({ client, kit }: { client: AgentClient; kit: AgentKit }) {
  const { server, url } = kit.connection;
  if (client === "claude-code") {
    return (
      <>
        <Step n={1} title="Agrega el tablero a Claude Code">
          <p className={muted}>Si el repo no lo creó el tablero:</p>
          <Code label="comando">{`claude mcp add --transport http --scope user ${server} ${url}`}</Code>
        </Step>
        <Step n={2} title="Inicia sesión">
          <Code label="comando">{`claude mcp login ${server}`}</Code>
          <p className={muted}>Sin ANTHROPIC_API_KEY definida, para usar tu plan y no una API key.</p>
        </Step>
      </>
    );
  }
  if (client === "codex") {
    return (
      <>
        <Step n={1} title="Agrega el tablero a Codex">
          <p className={muted}>Si el repo no lo creó el tablero:</p>
          <Code label="comando">{`codex mcp add ${server} --url ${url}`}</Code>
        </Step>
        <Step n={2} title="Inicia sesión">
          <Code label="comando">{`codex mcp login ${server}`}</Code>
        </Step>
      </>
    );
  }
  return (
    <Step n={1} title="Agrega el servidor en tu cliente">
      <p className={muted}>
        Servidor MCP por HTTP: <code className={mono}>{url}</code>. Sin OAuth, crea un token abajo y envíalo en <code className={mono}>Authorization: Bearer</code>.
      </p>
    </Step>
  );
}

/** Cómo pedir el trabajo en cada cliente. */
function Work({ client, kit }: { client: AgentClient; kit: AgentKit }) {
  const { server } = kit.connection;
  if (client === "claude-code") {
    return (
      <Step n={1} title="Pide tus pendientes">
        <Code label="comando">{"/pendientes"}</Code>
        <p className={muted}>
          Fuera de un repo con el kit: <code className={mono}>/mcp__{server}__pendientes</code>
        </p>
      </Step>
    );
  }
  return (
    <Step n={1} title="Pide tus pendientes">
      <Code label="pedido">{"Trabaja mis pendientes del tablero"}</Code>
    </Step>
  );
}

/**
 * Conectar el cliente de IA de una persona con el tablero: un comando y un inicio de sesión (OAuth). Los tokens
 * copiados a mano y el kit en .zip quedan para clientes sin OAuth y repos que no creó el tablero.
 */
export function AgentSetup({
  tokens,
  servers,
  initialKit,
  initialMcp,
  personName,
}: {
  tokens: RunnerToken[];
  servers: McpServer[];
  initialKit: AgentKit;
  initialMcp: string[];
  personName: string;
}) {
  const [client, setClient] = useState<AgentClient>("claude-code");
  const [mcp, setMcp] = useState(initialMcp);
  const [kit, setKit] = useState(initialKit);
  const [open, setOpen] = useState(initialKit.files[0]?.path ?? "");
  const [pending, start] = useTransition();
  const { tokenVar, server, url } = kit.connection;
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
    <div className="flex max-w-3xl flex-col gap-5 px-4 py-5 sm:px-6 sm:py-6">
      <Section title="1 · Conecta tu cliente de IA" description={`Trabaja en nombre de ${personName} y solo las tarjetas que supervisas.`}>
        <div className="flex flex-col gap-5">
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
          <ol className="flex flex-col gap-5">
            <Connect client={client} kit={kit} />
          </ol>
        </div>
      </Section>

      <Section title="2 · Trabaja tus pendientes" description="Consume los límites de tu cuenta en ese cliente.">
        <ol className="flex flex-col gap-5">
          <Work client={client} kit={kit} />
        </ol>
      </Section>

      <Section title="3 · Tus conexiones">
        <TokenManager
          tokens={tokens}
          create={createRunnerTokenAction}
          revoke={revokeRunnerTokenAction}
          copy={{
            empty: "Aún no conectaste ningún cliente.",
            namePlaceholder: "Nombre, ej. Cursor en la laptop",
            create: "Crear token",
            revokeTitle: (name) => `¿Desconectar «${name}»?`,
            revokeMessage: "Ese cliente dejará de tener acceso al tablero de inmediato.",
            revoked: "Conexión revocada",
          }}
          onIssued={(secret) => (
            <div className="mt-3 flex flex-col gap-1.5">
              <p className="text-fg">Guárdalo en tu shell (~/.zshrc), nunca en el repositorio:</p>
              <Code label="comando">{`export ${tokenVar}=${secret}`}</Code>
              <Code label="comando">{`claude mcp add --transport http --scope user ${server} ${url} --header "Authorization: Bearer \$${tokenVar}"`}</Code>
            </div>
          )}
        />
      </Section>

      <details className="group rounded-lg border border-line bg-surface">
        <summary className="cursor-pointer list-none px-5 py-4 text-[13px] font-medium text-fg-strong">
          Kit para un repo que no creó el tablero
          <span className="mt-0.5 block text-xs font-normal text-fg-muted">
            Copia estos archivos en la raíz del repo. No llevan secretos.
          </span>
        </summary>
        <div className="flex flex-col gap-4 border-t border-line px-5 py-4">
          <div className="flex flex-col gap-2">
            <p className="text-xs font-medium text-fg-muted">Servidores MCP adicionales</p>
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
            <Button tone="secondary" size="sm" icon={Download} href={`/local-agents/kit?${query}`} download>
              Descargar kit (.zip)
            </Button>
            <span className="text-xs text-fg-muted">{kit.files.length} archivos para {CLIENTS.find((c) => c.value === client)?.label}</span>
          </div>
        </div>
      </details>
    </div>
  );
}
