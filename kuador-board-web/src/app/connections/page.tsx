import { ChevronRight, GitBranch, Plug, Send, Sparkles } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";

import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireAdmin } from "@/lib/require-editor";

import { AiConnection } from "./AiConnection";
import { BusinessConnections } from "./BusinessConnections";
import { GitHubConnections } from "./GitHubConnections";
import { TelegramConnection } from "./TelegramConnection";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Conexiones" };

export default async function ConnectionsPage({ searchParams }: PageProps<"/connections">) {
  await requireAdmin();
  const params = await searchParams;
  let data;
  try {
    data = await Promise.all([
      boardApi.github(),
      boardApi.workspace(),
      boardApi.telegramSetup(),
      boardApi.workspaceAi(),
      boardApi.aiUsage().catch(() => null),
      boardApi.connections().catch(() => []),
      boardApi.connectionDirectory().catch(() => []),
      boardApi.projects().catch(() => []),
    ]);
  } catch (e) {
    return failPage(e);
  }
  const [github, workspace, telegram, ai, usage, connections, directory, projects] = data;
  const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? null;
  // Al volver de GitHub o de un sistema se abre directo su detalle.
  const view = one(params.ver) ?? (one(params.listo) || one(params.error) ? "github" : one(params.sistema) || one(params.errorSistema) ? "sistemas" : null);
  const bot = telegram.ownBot ?? telegram.platformBot;
  const cards = [
    {
      key: "ia",
      icon: Sparkles,
      title: "Inteligencia artificial",
      ok: ai.configured,
      status: ai.configured ? `${ai.provider ?? "Proveedor"} · ${ai.defaultModel}` : "Sin clave: los agentes no trabajan en el servidor",
    },
    {
      key: "github",
      icon: GitBranch,
      title: "GitHub",
      ok: github.connections.length > 0,
      status: github.connections.length > 0 ? `${github.connections.length} organización(es) conectada(s)` : "Sin conectar",
    },
    {
      key: "sistemas",
      icon: Plug,
      title: "Sistemas de la empresa",
      ok: connections.length > 0,
      status: connections.length > 0 ? `${connections.length} conectado(s)` : "Ninguno: correo, ERP, API…",
    },
    {
      key: "telegram",
      icon: Send,
      title: "Telegram",
      ok: telegram.groups.length > 0,
      status: telegram.groups.length > 0 ? `${telegram.groups.length} grupo(s) con avisos` : bot ? "Bot listo, sin grupos" : "Sin configurar",
    },
  ];
  const current = cards.find((c) => c.key === view);

  if (!current) {
    return (
      <>
        <PageHeader title="Conexiones" width="narrow" description="Lo que el espacio conecta una vez y usan todos sus tableros." />
        <ul className="grid max-w-3xl gap-3 px-4 py-5 sm:grid-cols-2 sm:px-6 sm:py-6">
          {cards.map((c) => (
            <li key={c.key}>
              <Link href={`/connections?ver=${c.key}`} className="flex h-full items-start gap-3 rounded-lg border border-line bg-surface p-4 hover:border-line-strong hover:bg-hover">
                <c.icon className="mt-0.5 size-5 shrink-0 text-fg-muted" aria-hidden />
                <span className="min-w-0 flex-1">
                  <span className="block text-[13px] font-medium text-fg-strong">{c.title}</span>
                  <span className="mt-0.5 flex items-center gap-1.5 text-xs text-fg-muted">
                    <span aria-hidden className={`size-2 shrink-0 rounded-full ${c.ok ? "bg-ok" : "bg-fg-faint"}`} />
                    {c.status}
                  </span>
                </span>
                <ChevronRight className="mt-0.5 size-4 shrink-0 text-fg-faint" aria-hidden />
              </Link>
            </li>
          ))}
        </ul>
      </>
    );
  }
  return (
    <>
      <PageHeader title={current.title} width="narrow" crumbs={[{ label: "Conexiones", href: "/connections" }]} />
      {view === "github" ? (
        <GitHubConnections overview={github} workspaceName={workspace.name} done={one(params.listo)} error={one(params.error)} />
      ) : (
        <div className="flex max-w-3xl flex-col gap-5 px-4 py-5 sm:px-6 sm:py-6">
          {view === "ia" && <AiConnection status={ai} usage={usage} />}
          {view === "sistemas" && (
            <BusinessConnections connections={connections} directory={directory} boards={projects.map((p) => ({ key: p.key, name: p.name }))} done={one(params.sistema)} error={one(params.errorSistema)} />
          )}
          {view === "telegram" && <TelegramConnection setup={telegram} />}
        </div>
      )}
    </>
  );
}
