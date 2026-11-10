"use client";

import { useRouter } from "next/navigation";
import { Plus, RotateCcw, Sparkle, Trash } from "lucide-react";
import { useState, useTransition } from "react";

import { deleteCatalogEntryAction, restoreCatalogEntryAction, saveCatalogEntryAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { Badge } from "@/components/ui/Section";
import { SidePanel } from "@/components/ui/SidePanel";
import { notify } from "@/components/ui/Toaster";
import { PROJECT_KIND_LABEL } from "@/lib/labels";
import type { AgentRole, CatalogEntries, CatalogType, McpServer, Methodology, WorkflowTemplate } from "@/lib/types";

import { CATALOG_SECTIONS, type CatalogSection } from "./catalogTypes";
import { McpEditor, MethodologyEditor, RoleEditor, TemplateEditor, blankEntry } from "./editors";

type AnyEntry = CatalogEntries[CatalogType];

/** Una sección del catálogo: tabla de entradas y editor al costado (crear, editar, restaurar, borrar). */
export function CatalogView({ slug, entries, roles }: { slug: CatalogSection["slug"]; entries: AnyEntry[]; roles: AgentRole[] }) {
  const section = CATALOG_SECTIONS.find((s) => s.slug === slug)!;
  const router = useRouter();
  const [draft, setDraft] = useState<AnyEntry | null>(null);
  const [isNew, setIsNew] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<"delete" | "restore" | null>(null);
  const [pending, startTransition] = useTransition();

  const open = (entry: AnyEntry | null) => {
    setIsNew(entry == null);
    setDraft(entry ? structuredClone(entry) : blankEntry(section.type));
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
      const r = await saveCatalogEntryAction(section.type, draft as never);
      if (r.ok) done(isNew ? `«${draft.name}» creado` : `«${draft.name}» guardado`);
      else setError(r.error);
    });

  return (
    <div className="flex flex-col gap-4 px-6 py-6">
      <div className="flex flex-wrap items-center gap-3">
        <p className="min-w-0 flex-1 text-[13px] text-fg-muted">{section.description}</p>
        <Button tone="primary" size="sm" icon={Plus} onClick={() => open(null)}>
          {section.newLabel}
        </Button>
      </div>

      <div className="overflow-hidden rounded-lg border border-line bg-surface">
        <table className="w-full text-left text-[13px]">
          <thead className="border-b border-line bg-sunken text-xs text-fg-muted">
            <tr>
              <th scope="col" className="px-5 py-2 font-medium">Nombre</th>
              <th scope="col" className="hidden w-44 px-3 py-2 font-medium sm:table-cell">Clave</th>
              <th scope="col" className="hidden px-3 py-2 font-medium md:table-cell">Detalle</th>
              <th scope="col" className="w-28 px-5 py-2 text-right font-medium">Origen</th>
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
                <td className="px-5 py-2.5 text-right">{e.builtIn ? <Badge>De fábrica</Badge> : <Badge tone="info">Propia</Badge>}</td>
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
              {!isNew && !draft.builtIn && (
                <Button tone="danger" size="sm" icon={Trash} disabled={pending} onClick={() => setConfirming("delete")}>
                  Eliminar
                </Button>
              )}
              {!isNew && draft.builtIn && (
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
            {section.type === "mcp-servers" && <McpEditor value={draft as McpServer} onChange={setDraft} isNew={isNew} />}
          </div>
        )}
      </SidePanel>

      <ConfirmDialog
        open={confirming === "delete"}
        onCancel={() => setConfirming(null)}
        title={`¿Eliminar «${draft?.name}»?`}
        message="Solo se puede borrar si ningún proyecto o miembro la usa. No se puede deshacer."
        confirmLabel="Eliminar"
        pending={pending}
        onConfirm={() =>
          draft &&
          startTransition(async () => {
            const r = await deleteCatalogEntryAction(section.type, draft.key);
            setConfirming(null);
            if (r.ok) done(`«${draft.name}» eliminado`);
            else setError(r.error);
          })
        }
      />
      <ConfirmDialog
        open={confirming === "restore"}
        onCancel={() => setConfirming(null)}
        title={`¿Restaurar «${draft?.name}»?`}
        message="Vuelve a los valores de fábrica del catálogo. Se pierden los cambios que el equipo le hizo."
        confirmLabel="Restaurar"
        pending={pending}
        onConfirm={() =>
          draft &&
          startTransition(async () => {
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
      return `${m.documents.length - issue} del proyecto · ${issue} por issue`;
    }
    case "roles": {
      const r = e as AgentRole;
      return r.automation === "NONE" ? (
        "Sin automatización"
      ) : (
        <span className="inline-flex items-center gap-1 text-sol-text">
          <Sparkle className="size-3" aria-hidden /> Refina issues asignados
        </span>
      );
    }
    case "mcp-servers": {
      const s = e as McpServer;
      return s.transport === "STDIO" ? <code className="font-mono">{[s.command, ...s.args].join(" ")}</code> : <code className="font-mono">{s.url}</code>;
    }
  }
}
