"use client";

import { Copy, Download } from "lucide-react";
import { clsx } from "clsx";
import { useState, useTransition } from "react";

import { claudeKitAction, createRunnerTokenAction, revokeRunnerTokenAction } from "@/app/actions";
import { TokenManager } from "@/components/tokens/TokenManager";
import { Button } from "@/components/ui/Button";
import { Switch } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { KitFile, McpServer, RunnerToken } from "@/lib/types";

/** Bloque de comando o archivo con botón de copiar. */
function Code({ children, label }: { children: string; label: string }) {
  return (
    <div className="group relative">
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

/**
 * Conectar Claude Code con el tablero: la conexión (token) de la persona, el kit para el repositorio y
 * cómo trabajar los pendientes.
 */
export function ClaudeCodeSetup({
  tokens,
  servers,
  initialFiles,
  initialMcp,
  boardUrl,
  personName,
}: {
  tokens: RunnerToken[];
  servers: McpServer[];
  initialFiles: KitFile[];
  initialMcp: string[];
  boardUrl: string;
  personName: string;
}) {
  const [mcp, setMcp] = useState(initialMcp);
  const [files, setFiles] = useState(initialFiles);
  const [open, setOpen] = useState(initialFiles[0]?.path ?? "");
  const [pending, start] = useTransition();
  const query = new URLSearchParams(mcp.map((k) => ["mcp", k])).toString();
  const shown = files.find((f) => f.path === open) ?? files[0];

  const toggle = (key: string, on: boolean) => {
    const next = on ? [...mcp, key] : mcp.filter((k) => k !== key);
    setMcp(next);
    start(async () => {
      const r = await claudeKitAction(next);
      if (r.ok) setFiles(r.data);
      else notify.error("No se pudo actualizar el kit", r.error);
    });
  };

  return (
    <div className="flex max-w-3xl flex-col gap-5 px-6 py-6">
      <Section title="1 · Tu conexión" description={`Claude Code entra al tablero como los agentes del equipo, en nombre de ${personName}. Solo abre el servidor MCP del tablero: no sirve para entrar a la web ni a la API.`}>
        <TokenManager
          tokens={tokens}
          create={createRunnerTokenAction}
          revoke={revokeRunnerTokenAction}
          copy={{
            empty: "Aún no conectaste ningún equipo.",
            namePlaceholder: "Nombre del equipo, ej. MacBook de Diego",
            create: "Crear conexión",
            revokeTitle: (name) => `¿Desconectar «${name}»?`,
            revokeMessage: "Claude Code dejará de tener acceso al tablero desde ese equipo de inmediato.",
            revoked: "Conexión revocada",
          }}
          onIssued={(secret) => (
            <div className="mt-3 flex flex-col gap-1.5">
              <p className="text-fg">Guárdalo en tu shell (por ejemplo en ~/.zshrc), nunca en el repositorio:</p>
              <Code label="comando">{`export KUADOR_TOKEN=${secret}`}</Code>
            </div>
          )}
        />
      </Section>

      <Section title="2 · Kit para el repositorio" description="Archivos para la raíz del repo. No llevan secretos: el token y las claves salen de variables de entorno de cada persona, así que se pueden versionar.">
        <div className="flex flex-col gap-4">
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
                  <Switch checked={mcp.includes(s.key)} onChange={(on) => toggle(s.key, on)} label={`Incluir ${s.name}`} hideLabel disabled={pending} />
                </li>
              ))}
            </ul>
          </div>

          <div className="overflow-hidden rounded-md border border-line">
            <div role="tablist" aria-label="Archivos del kit" className="flex gap-1 overflow-x-auto border-b border-line bg-sunken px-2 py-1.5">
              {files.map((f) => (
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
            <Button tone="primary" size="sm" icon={Download} href={`/claude-code/kit?${query}`} download>
              Descargar kit (.zip)
            </Button>
            <span className="text-xs text-fg-muted">
              {files.filter((f) => f.path.startsWith(".claude/agents/")).length} subagentes, el comando /pendientes y .mcp.json
            </span>
          </div>
          <div className="flex flex-col gap-1.5">
            <p className="text-xs text-fg-muted">O desde la raíz del repo, con tu conexión:</p>
            <Code label="comando">{`curl -fsSL -H "Authorization: Bearer $KUADOR_TOKEN" "${boardUrl}/api/claude-kit.zip?${query}" -o kuador-kit.zip && unzip -o kuador-kit.zip && rm kuador-kit.zip`}</Code>
          </div>
        </div>
      </Section>

      <Section title="3 · Trabaja tus pendientes">
        <ol className="flex flex-col gap-5">
          <Step n={1} title="Usa tu suscripción, no la API">
            <p className="text-[13px] text-fg-muted">Si tienes ANTHROPIC_API_KEY definida, Claude Code cobra cada uso a esa clave en vez de a tu plan.</p>
            <Code label="comando">unset ANTHROPIC_API_KEY</Code>
          </Step>
          <Step n={2} title="Abre Claude Code en el repo y pide tus pendientes">
            <Code label="comando">{"claude\n/pendientes"}</Code>
            <p className="text-[13px] text-fg-muted">
              Filtra con <code className="font-mono text-[12px]">/pendientes MED</code> (un proyecto) o <code className="font-mono text-[12px]">/pendientes QA</code> (un rol). La primera vez, Claude Code te pide aprobar el servidor «kuador» del .mcp.json.
            </p>
          </Step>
          <Step n={3} title="Qué atiende">
            <p className="text-[13px] leading-relaxed text-fg-muted">
              Los issues que supervisas: los que asignaste a un agente o llevaste a una etapa que cubren agentes (por ejemplo, al aprobar una puerta). Cada issue lo trabaja el subagente de su rol, firmado por ese agente, y termina entregado, devuelto o con una pregunta para ti. Las puertas siguen siendo de las personas. Todo cuenta para los límites de tu plan.
            </p>
          </Step>
        </ol>
      </Section>
    </div>
  );
}
