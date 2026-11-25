"use client";

import { useRouter } from "next/navigation";
import { Plus, RotateCcw, Sparkle, Trash } from "lucide-react";
import { useState, useTransition } from "react";

import {
  deleteCatalogEntryAction,
  discardPlatformCatalogEntryAction,
  removePlatformCatalogEntryAction,
  restoreCatalogEntryAction,
  saveCatalogEntryAction,
  savePlatformCatalogEntryAction,
} from "@/app/actions";
import { Field, TextInput } from "@/components/ui/Field";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { Badge } from "@/components/ui/Section";
import { SidePanel } from "@/components/ui/SidePanel";
import { notify } from "@/components/ui/Toaster";
import { PROJECT_KIND_LABEL } from "@/lib/labels";
import type { AgentPolicy, AgentRole, CatalogEntries, CatalogEntryStatus, CatalogType, ComponentTemplate, McpServer, Methodology, PlatformEntryStatus, WorkflowTemplate } from "@/lib/types";

import { CATALOG_SECTIONS, type CatalogSection } from "./catalogTypes";
import { McpEditor, MethodologyEditor, RoleEditor, TemplateEditor, blankEntry, ComponentEditor, COMPONENT_KIND_LABEL } from "./editors";
import { EntryHistory } from "./EntryHistory";
import { PolicyEditor } from "./PolicyEditor";

type AnyEntry = CatalogEntries[CatalogType];

/** En la plataforma: estado de cada entrada frente a lo publicado y, en roles, el agente que nace con cada espacio. */
export type PlatformInfo = Record<string, { status: PlatformEntryStatus; memberName: string | null; color: string | null }>;

const STATUS_BADGE: Record<PlatformEntryStatus, { label: string; tone: "neutral" | "info" | "sol" }> = {
  PUBLISHED: { label: "Publicada", tone: "neutral" },
  MODIFIED: { label: "Cambios sin publicar", tone: "sol" },
  NEW: { label: "Nueva", tone: "info" },
};

/** Qué pasa al borrar, por tipo: lo que la API comprueba de verdad. */
const DELETE_NOTE: Record<CatalogSection["type"], string> = {
  "workflow-templates": "Los tableros que nacieron de ella no cambian: cada uno tiene su copia. No se puede deshacer.",
  methodologies: "Solo se puede borrar si ningún tablero lo usa. No se puede deshacer.",
  roles: "Solo se puede borrar si ningún miembro tiene este rol. No se puede deshacer.",
  "component-templates": "Solo se puede borrar si ninguna pieza de un tablero salió de ella. No se puede deshacer.",
  "mcp-servers": "Solo se puede borrar si ninguna pieza del catálogo lo lleva en su kit. No se puede deshacer.",
  "agent-policies": "No se puede deshacer.",
};

/**
 * Una sección del catálogo: tabla de entradas y editor al costado. En un espacio se crea, edita, restaura y borra;
 * en la plataforma ({@code platform}) se edita en borrador, se descartan cambios o se quita la entrada.
 */
export function CatalogView({
  slug,
  entries,
  roles,
  platform,
  core = [],
  status = {},
}: {
  slug: CatalogSection["slug"];
  entries: AnyEntry[];
  roles: AgentRole[];
  platform?: PlatformInfo;
  /** Reglas de agentes: el núcleo de seguridad que no se edita. */
  core?: string[];
  /** En un espacio: de dónde viene cada entrada y cuánto se usa. */
  status?: Record<string, CatalogEntryStatus>;
}) {
  const section = CATALOG_SECTIONS.find((s) => s.slug === slug)!;
  // La plataforma quitó esta entrada de fábrica: no hay a qué restaurarla, así que se puede eliminar.
  const retired = (key: string) => status[key]?.origin === "FACTORY_RETIRED";
  const router = useRouter();
  const [extra, setExtra] = useState<{ memberName: string; color: string }>({ memberName: "", color: "" });
  const [draft, setDraft] = useState<AnyEntry | null>(null);
  const [isNew, setIsNew] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<"delete" | "restore" | null>(null);
  const [pending, startTransition] = useTransition();

  const open = (entry: AnyEntry | null) => {
    setIsNew(entry == null);
    setDraft(entry ? structuredClone(entry) : blankEntry(section.type));
    const info = entry && platform?.[entry.key];
    setExtra({ memberName: info?.memberName ?? "", color: info?.color ?? "" });
    setError(null);
  };
  const close = () => setDraft(null);
  const done = (message: string) => {
    notify.ok(message);
    close();
    router.refresh();
  };

  const save = () =>
    draft &&
    startTransition(async () => {
      const r = platform
        ? await savePlatformCatalogEntryAction(section.type, draft.key, draft, section.type === "roles" ? { memberName: extra.memberName || null, color: extra.color || null } : {})
        : await saveCatalogEntryAction(section.type, draft as never);
      if (r.ok) done(platform ? `«${draft.name}» guardado en borrador` : isNew ? `«${draft.name}» creado` : `«${draft.name}» guardado`);
      else setError(r.error);
    });

  return (
    <div className="flex flex-col gap-4 px-4 py-5 sm:px-6 sm:py-6">
      <div className="flex flex-wrap items-center gap-3">
        <p className="min-w-0 flex-1 text-[13px] text-fg-muted">{section.description}</p>
        {section.newLabel && (
          <Button tone="primary" size="sm" icon={Plus} onClick={() => open(null)}>
            {section.newLabel}
          </Button>
        )}
      </div>

      <div className="overflow-hidden rounded-lg border border-line bg-surface">
        <table className="w-full text-left text-[13px]">
          <thead className="border-b border-line bg-sunken text-xs text-fg-muted">
            <tr>
              <th scope="col" className="px-5 py-2 font-medium">Nombre</th>
              <th scope="col" className="hidden w-44 px-3 py-2 font-medium sm:table-cell">Clave</th>
              <th scope="col" className="hidden px-3 py-2 font-medium md:table-cell">Detalle</th>
              <th scope="col" className="w-40 px-5 py-2 text-right font-medium">{platform ? "Estado" : "Origen"}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {entries.map((e) => (
              <tr key={e.key} className="relative hover:bg-hover">
                <td className="px-5 py-2.5">
                  <button type="button" onClick={() => open(e)} className="text-left font-medium text-fg-strong after:absolute after:inset-0">
                    {e.name}
                  </button>
                </td>
                <td className="tabular hidden px-3 py-2.5 font-mono text-xs text-fg-muted sm:table-cell">{e.key}</td>
                <td className="hidden px-3 py-2.5 text-xs text-fg-muted md:table-cell">{detail(section.type, e)}</td>
                <td className="px-5 py-2.5 text-right">
                  {platform ? (
                    <Badge tone={STATUS_BADGE[platform[e.key]?.status ?? "PUBLISHED"].tone}>{STATUS_BADGE[platform[e.key]?.status ?? "PUBLISHED"].label}</Badge>
                  ) : e.builtIn ? (
                    <Badge>De fábrica</Badge>
                  ) : (
                    <Badge tone="info">Propia</Badge>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <SidePanel
        open={draft != null}
        onClose={close}
        title={draft ? (isNew ? section.newLabel : draft.name || draft.key) : ""}
        footer={
          draft && (
            <div className="flex flex-wrap items-center gap-2">
              {platform && !isNew && (
                <Button tone="danger" size="sm" icon={Trash} disabled={pending} onClick={() => setConfirming("delete")}>
                  Quitar del catálogo
                </Button>
              )}
              {platform && !isNew && platform[draft.key]?.status !== "PUBLISHED" && (
                <Button tone="ghost" size="sm" icon={RotateCcw} disabled={pending} onClick={() => setConfirming("restore")}>
                  Descartar cambios
                </Button>
              )}
              {!platform && !isNew && (!draft.builtIn || retired(draft.key)) && (
                <Button tone="danger" size="sm" icon={Trash} disabled={pending} onClick={() => setConfirming("delete")}>
                  Eliminar
                </Button>
              )}
              {!platform && !isNew && draft.builtIn && !retired(draft.key) && (
                <Button tone="ghost" size="sm" icon={RotateCcw} disabled={pending} onClick={() => setConfirming("restore")}>
                  Restaurar valores de fábrica
                </Button>
              )}
              {error ? (
                <p role="alert" className="min-w-0 flex-1 text-right text-[13px] text-danger">
                  {error}
                </p>
              ) : (
                <span className="flex-1" />
              )}
              <Button tone="ghost" size="sm" onClick={close}>
                Cancelar
              </Button>
              <Button tone="primary" size="sm" loading={pending} onClick={save}>
                {isNew ? "Crear" : "Guardar"}
              </Button>
            </div>
          )
        }
      >
        {draft && (
          <div className="px-5 py-5">
            {section.type === "workflow-templates" && <TemplateEditor value={draft as WorkflowTemplate} onChange={setDraft} isNew={isNew} />}
            {section.type === "methodologies" && <MethodologyEditor value={draft as Methodology} onChange={setDraft} isNew={isNew} roles={roles} />}
            {section.type === "roles" && <RoleEditor value={draft as AgentRole} onChange={setDraft} isNew={isNew} />}
            {platform && section.type === "roles" && (
              <div className="mt-5 grid gap-4 border-t border-line pt-5 sm:grid-cols-2">
                <Field label="Agente que nace con cada espacio" hint="Vacío = «Agente» y el nombre del rol.">
                  {(id) => <TextInput id={id} maxLength={120} value={extra.memberName} onChange={(e) => setExtra({ ...extra, memberName: e.target.value })} />}
                </Field>
                <Field label="Color del agente" hint="Formato #RRGGBB.">
                  {(id) => <TextInput id={id} maxLength={7} value={extra.color} onChange={(e) => setExtra({ ...extra, color: e.target.value })} placeholder="#8fb4ff" className="font-mono" />}
                </Field>
              </div>
            )}
            {section.type === "mcp-servers" && <McpEditor value={draft as McpServer} onChange={setDraft} isNew={isNew} />}
            {section.type === "component-templates" && <ComponentEditor value={draft as ComponentTemplate} onChange={setDraft} isNew={isNew} />}
            {section.type === "agent-policies" && <PolicyEditor value={draft as AgentPolicy} onChange={setDraft} core={core} />}
            {/* El historial: comparar con versiones anteriores y volver a una, sin miedo a romper un agente. */}
            {!platform && !isNew && (
              <details className="mt-6 border-t border-line pt-4">
                <summary className="cursor-pointer text-[13px] font-medium text-fg-strong">Historial de versiones</summary>
                <div className="mt-3">
                  <EntryHistory type={section.type} entryKey={draft.key} current={draft} onRestored={() => done("Versión restaurada")} />
                </div>
              </details>
            )}
          </div>
        )}
      </SidePanel>

      <ConfirmDialog
        open={confirming === "delete"}
        onCancel={() => setConfirming(null)}
        title={platform ? `¿Quitar «${draft?.name}» del catálogo?` : `¿Eliminar «${draft?.name}»?`}
        message={
          platform
            ? "Los espacios nuevos ya no la reciben; los que ya la tienen la conservan. No se puede deshacer."
            : DELETE_NOTE[section.type]
        }
        confirmLabel={platform ? "Quitar" : "Eliminar"}
        pending={pending}
        onConfirm={() =>
          draft &&
          startTransition(async () => {
            const r = platform ? await removePlatformCatalogEntryAction(section.type, draft.key) : await deleteCatalogEntryAction(section.type, draft.key);
            setConfirming(null);
            if (r.ok) done(platform ? `«${draft.name}» quitado del catálogo` : `«${draft.name}» eliminado`);
            else setError(r.error);
          })
        }
      />
      <ConfirmDialog
        open={confirming === "restore"}
        onCancel={() => setConfirming(null)}
        title={platform ? `¿Descartar los cambios de «${draft?.name}»?` : `¿Restaurar «${draft?.name}»?`}
        message={
          platform
            ? "Vuelve a lo publicado (o la quita si nunca se publicó). Se pierden los cambios en borrador."
            : "Vuelve a los valores de fábrica del catálogo. Se pierden los cambios que el equipo le hizo."
        }
        confirmLabel={platform ? "Descartar" : "Restaurar"}
        pending={pending}
        onConfirm={() =>
          draft &&
          startTransition(async () => {
            if (platform) {
              const r = await discardPlatformCatalogEntryAction(section.type, draft.key);
              setConfirming(null);
              if (r.ok) done(`Cambios de «${draft.name}» descartados`);
              else setError(r.error);
              return;
            }
            const r = await restoreCatalogEntryAction(section.type, draft.key);
            setConfirming(null);
            if (r.ok) done(`«${r.data.name}» restaurado`);
            else setError(r.error);
          })
        }
      />
    </div>
  );
}

/** Una línea de resumen por tipo, para leer la tabla sin abrir cada entrada. */
function detail(type: CatalogType, e: AnyEntry): React.ReactNode {
  switch (type) {
    case "workflow-templates": {
      const t = e as WorkflowTemplate;
      return `${PROJECT_KIND_LABEL[t.kind]} · ${t.columns.map((c) => c.name).join(" → ")}`;
    }
    case "methodologies": {
      const m = e as Methodology;
      const issue = m.documents.filter((d) => d.scope === "ISSUE").length;
      return `${m.documents.length - issue} del proyecto · ${issue} por tarjeta`;
    }
    case "roles": {
      const r = e as AgentRole;
      return r.automation === "NONE" ? (
        "Sin automatización"
      ) : (
        <span className="inline-flex items-center gap-1 text-sol-text">
          <Sparkle className="size-3" aria-hidden /> Refina las tarjetas asignadas
        </span>
      );
    }
    case "component-templates": {
      const c = e as ComponentTemplate;
      return (
        <span>
          {COMPONENT_KIND_LABEL[c.kind]} · {c.stack} ·{" "}
          {c.templateRepo ? <code className="font-mono">{`${c.templateRepo}@${c.templateRef}`}</code> : <span className="text-fg-faint">sin código base</span>}
        </span>
      );
    }
    case "agent-policies": {
      const p = e as AgentPolicy;
      return `${p.rules.common.length + p.rules.questions.length + p.rules.board.length} reglas · ${p.limits.questionRounds} vueltas de preguntas · ${p.limits.turnsClaude} vueltas del modelo`;
    }
    case "mcp-servers": {
      const s = e as McpServer;
      return s.transport === "STDIO" ? <code className="font-mono">{[s.command, ...s.args].join(" ")}</code> : <code className="font-mono">{s.url}</code>;
    }
  }
}
