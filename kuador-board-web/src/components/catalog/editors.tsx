"use client";

import { ArrowDown, ArrowUp, ChevronDown, ChevronRight, Plus, Trash } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/Button";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { Field, SelectInput, TextArea, TextInput } from "@/components/ui/Field";
import { TypeIcon } from "@/components/ui/IssueIcons";
import { Badge } from "@/components/ui/Section";
import { CATEGORY_LABEL, typeLabel } from "@/lib/labels";
import {
  COLUMN_CATEGORIES,
  ISSUE_TYPES,
  type AgentRole,
  type BoardColumn,
  type CatalogEntries,
  type CatalogType,
  type DocumentDef,
  type ComponentKind,
  type ComponentTemplate,
  type McpServer,
  type Methodology,
  type WorkflowTemplate,
} from "@/lib/types";

import { KEY_PATTERN } from "./catalogTypes";

// ---- Entradas vacías para «Nuevo…» ----

const base = { id: null, key: "", name: "", builtIn: false, version: 0 };

export function blankEntry(type: CatalogType): CatalogEntries[CatalogType] {
  switch (type) {
    case "workflow-templates":
      return {
        ...base,
        description: null,
        kind: "GENERAL",
        columns: [
          { key: "TODO", name: "Por hacer", category: "TODO" },
          { key: "DOING", name: "En curso", category: "IN_PROGRESS" },
          { key: "DONE", name: "Hecho", category: "DONE" },
        ],
        issueTypes: [{ type: "TASK", label: "Tarea" }],
        stages: [],
        transitions: [],
      } satisfies WorkflowTemplate;
    case "methodologies":
      return { ...base, summary: "", agentRules: [], documents: [] } satisfies Methodology;
    case "roles":
      return { ...base, description: "", instructions: "", tools: ["Read", "Grep", "Glob"], automation: "NONE" } satisfies AgentRole;
    case "mcp-servers":
      return { ...base, description: "", transport: "STDIO", command: "npx", args: [], url: null, envVars: [], docsUrl: null } satisfies McpServer;
    case "component-templates":
      return {
        ...base,
        description: "",
        kind: "BACKEND",
        stack: "",
        templateRepo: null,
        templateRef: null,
        variables: [],
        commands: { build: null, test: null, run: null },
        ciChecks: ["ci"],
        suggestedRoles: [],
        mcpServers: [],
      } satisfies ComponentTemplate;
    case "agent-policies":
      // Una sola entrada por espacio (DEFAULT): no se crea desde «Nuevo…».
      throw new Error("Las reglas de agentes no se crean: se editan.");
  }
}

// ---- Piezas comunes ----

/** Clave + nombre: la clave solo se elige al crear (es la referencia estable desde proyectos y miembros). */
function Identity<T extends { key: string; name: string }>({ value, onChange, isNew, keyHint }: { value: T; onChange: (v: T) => void; isNew: boolean; keyHint: string }) {
  return (
    <div className="grid gap-3 sm:grid-cols-[12rem_minmax(0,1fr)]">
      <Field label="Clave" hint={isNew ? keyHint : "No se puede cambiar."}>
        {(id) => (
          <TextInput
            id={id}
            required
            readOnly={!isNew}
            pattern={KEY_PATTERN}
            title="Mayúsculas, números o guion bajo, empezando por letra"
            value={value.key}
            onChange={(e) => onChange({ ...value, key: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, "_") })}
            className="font-mono uppercase"
          />
        )}
      </Field>
      <Field label="Nombre">{(id) => <TextInput id={id} required maxLength={80} value={value.name} onChange={(e) => onChange({ ...value, name: e.target.value })} />}</Field>
    </div>
  );
}

/** Lista de textos editada como una línea por elemento. */
export function LinesField({ label, hint, value, onChange, rows = 4, mono }: { label: string; hint?: string; value: string[]; onChange: (v: string[]) => void; rows?: number; mono?: boolean }) {
  const [text, setText] = useState(value.join("\n"));
  return (
    <Field label={label} hint={hint ?? "Uno por línea."}>
      {(id) => (
        <TextArea
          id={id}
          rows={rows}
          value={text}
          onChange={(e) => {
            setText(e.target.value);
            onChange(e.target.value.split("\n").map((l) => l.trim()).filter(Boolean));
          }}
          className={mono ? "font-mono text-xs" : undefined}
        />
      )}
    </Field>
  );
}

export function Group({ title, description, children, actions }: { title: string; description?: string; children: React.ReactNode; actions?: React.ReactNode }) {
  return (
    <fieldset className="flex flex-col gap-3 border-t border-line pt-5">
      <div className="flex items-start justify-between gap-3">
        <div>
          <legend className="text-[13px] font-semibold text-fg-strong">{title}</legend>
          {description && <p className="mt-0.5 text-xs leading-relaxed text-fg-muted">{description}</p>}
        </div>
        {actions}
      </div>
      {children}
    </fieldset>
  );
}

const keyFromName = (name: string) =>
  name
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 40) || "COLUMNA";

const CATEGORY_DOT = { TODO: "bg-fg-faint", IN_PROGRESS: "bg-info", DONE: "bg-ok" } as const;

// ---- Plantilla de flujo ----

export function TemplateEditor({ value, onChange, isNew }: { value: WorkflowTemplate; onChange: (v: WorkflowTemplate) => void; isNew: boolean }) {
  const setColumns = (columns: BoardColumn[]) => onChange({ ...value, columns });
  const moveColumn = (i: number, delta: number) => {
    const next = [...value.columns];
    const [c] = next.splice(i, 1);
    next.splice(i + delta, 0, c);
    setColumns(next);
  };
  return (
    <div className="flex flex-col gap-5">
      <Identity value={value} onChange={onChange} isNew={isNew} keyHint="Ej. KANBAN o SOPORTE." />
      <Field label="Descripción">
        {(id) => <TextInput id={id} maxLength={300} value={value.description ?? ""} onChange={(e) => onChange({ ...value, description: e.target.value || null })} />}
      </Field>
      <div className="flex flex-col gap-1.5">
        <span className="text-xs font-medium text-fg-muted">Tipo de proyecto</span>
        <ChoiceCards
          name="template-kind"
          label="Tipo de proyecto"
          columns={2}
          value={value.kind}
          onChange={(kind) => onChange({ ...value, kind })}
          choices={[
            { value: "SOFTWARE", label: "Software", hint: "Con metodología, documentos y repositorio." },
            { value: "GENERAL", label: "General", hint: "Ventas, operaciones, tareas: solo el tablero." },
          ]}
        />
      </div>

      <Group
        title="Columnas"
        description="Empieza en «Por empezar» y termina en «Terminado»."
        actions={
          <Button size="sm" icon={Plus} disabled={value.columns.length >= 12} onClick={() => setColumns([...value.columns, { key: "", name: "", category: "IN_PROGRESS" }])}>
            Columna
          </Button>
        }
      >
        <ol className="flex flex-col divide-y divide-line rounded-md border border-line">
          {value.columns.map((c, i) => (
            <li key={i} className="grid grid-cols-[minmax(0,1fr)_10rem_9rem_auto] items-center gap-2 px-2 py-1.5">
              <TextInput
                size="sm"
                required
                maxLength={40}
                value={c.name}
                aria-label={`Nombre de la columna ${i + 1}`}
                onChange={(e) => setColumns(value.columns.map((x, j) => (j === i ? { ...x, name: e.target.value, key: x.key && !isNew ? x.key : keyFromName(e.target.value) } : x)))}
              />
              <code className="truncate font-mono text-[11px] text-fg-faint" title="Clave de la columna">
                {c.key || "—"}
              </code>
              <SelectInput
                size="sm"
                ariaLabel={`Categoría de ${c.name || `la columna ${i + 1}`}`}
                options={COLUMN_CATEGORIES.map((k) => ({ value: k, label: CATEGORY_LABEL[k], leading: <span aria-hidden className={`size-2 rounded-full ${CATEGORY_DOT[k]}`} /> }))}
                value={c.category}
                onChange={(category) => category && setColumns(value.columns.map((x, j) => (j === i ? { ...x, category } : x)))}
              />
              <span className="flex">
                <Button tone="ghost" size="sm" icon={ArrowUp} iconOnly label="Subir" disabled={i === 0} onClick={() => moveColumn(i, -1)} />
                <Button tone="ghost" size="sm" icon={ArrowDown} iconOnly label="Bajar" disabled={i === value.columns.length - 1} onClick={() => moveColumn(i, 1)} />
                <Button tone="ghost" size="sm" icon={Trash} iconOnly label="Quitar" disabled={value.columns.length <= 2} onClick={() => setColumns(value.columns.filter((_, j) => j !== i))} />
              </span>
            </li>
          ))}
        </ol>
      </Group>

      <Group title="Tipos de trabajo">
        <ul className="flex flex-col gap-2">
          {ISSUE_TYPES.map((type) => {
            const current = value.issueTypes.find((t) => t.type === type);
            return (
              <li key={type} className="grid grid-cols-[1.25rem_6rem_minmax(0,1fr)] items-center gap-2">
                <input
                  type="checkbox"
                  checked={!!current}
                  aria-label={`Usar ${typeLabel(type)}`}
                  onChange={(e) =>
                    onChange({
                      ...value,
                      issueTypes: e.target.checked ? [...value.issueTypes, { type, label: typeLabel(type) }] : value.issueTypes.filter((t) => t.type !== type),
                    })
                  }
                  className="size-4 accent-sol"
                />
                <span className="flex items-center gap-1.5 text-xs text-fg-muted">
                  <TypeIcon type={type} /> {type}
                </span>
                <TextInput
                  size="sm"
                  disabled={!current}
                  maxLength={40}
                  aria-label={`Nombre de ${type}`}
                  value={current?.label ?? typeLabel(type)}
                  onChange={(e) => onChange({ ...value, issueTypes: value.issueTypes.map((t) => (t.type === type ? { ...t, label: e.target.value } : t)) })}
                />
              </li>
            );
          })}
        </ul>
      </Group>
    </div>
  );
}

// ---- Metodología ----

const blankDoc = (): DocumentDef => ({
  kind: "",
  label: "",
  scope: "ISSUE",
  required: false,
  purpose: "",
  pathPattern: "docs/{task}.md",
  template: "# {title}\n\n",
  writtenBy: null,
  condition: "ALWAYS",
  agentInstructions: null,
});

export function MethodologyEditor({ value, onChange, isNew, roles }: { value: Methodology; onChange: (v: Methodology) => void; isNew: boolean; roles: AgentRole[] }) {
  const [openDoc, setOpenDoc] = useState<number | null>(null);
  const setDoc = (i: number, patch: Partial<DocumentDef>) => onChange({ ...value, documents: value.documents.map((d, j) => (j === i ? { ...d, ...patch } : d)) });
  const roleOptions = [{ value: "", label: "Una persona" }, ...roles.map((r) => ({ value: r.key, label: r.name }))];

  return (
    <div className="flex flex-col gap-5">
      <Identity value={value} onChange={onChange} isNew={isNew} keyHint="Ej. KANBAN_ADR." />
      <Field label="Resumen">
        {(id) => <TextArea id={id} rows={3} value={value.summary} onChange={(e) => onChange({ ...value, summary: e.target.value })} />}
      </Field>
      <LinesField label="Reglas para los agentes" hint="Una por línea." value={value.agentRules} onChange={(agentRules) => onChange({ ...value, agentRules })} rows={5} />

      <Group
        title={`Documentos · ${value.documents.length}`}
        actions={
          <Button
            size="sm"
            icon={Plus}
            onClick={() => {
              onChange({ ...value, documents: [...value.documents, blankDoc()] });
              setOpenDoc(value.documents.length);
            }}
          >
            Documento
          </Button>
        }
      >
        <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
          {value.documents.map((d, i) => {
            const expanded = openDoc === i;
            return (
              <li key={i}>
                <button type="button" aria-expanded={expanded} onClick={() => setOpenDoc(expanded ? null : i)} className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-hover">
                  {expanded ? <ChevronDown className="size-4 text-fg-faint" aria-hidden /> : <ChevronRight className="size-4 text-fg-faint" aria-hidden />}
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2 text-[13px] font-medium text-fg-strong">
                      {d.label || "Documento sin nombre"}
                      <Badge>{d.scope === "PROJECT" ? "Proyecto" : "Issue"}</Badge>
                      {d.required && <Badge tone="warn">Obligatorio</Badge>}
                    </span>
                    <code className="block truncate font-mono text-[11px] text-fg-faint">{d.pathPattern}</code>
                  </span>
                  <span className="text-xs text-fg-muted">{d.writtenBy ? (roles.find((r) => r.key === d.writtenBy)?.name ?? d.writtenBy) : "Persona"}</span>
                </button>
                {expanded && (
                  <div className="flex flex-col gap-3 border-t border-line bg-sunken px-4 py-4">
                    <div className="grid gap-3 sm:grid-cols-[10rem_minmax(0,1fr)]">
                      <Field label="Tipo (clave)">
                        {(id) => (
                          <TextInput id={id} size="sm" required pattern={KEY_PATTERN} value={d.kind} onChange={(e) => setDoc(i, { kind: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, "_") })} className="font-mono uppercase" />
                        )}
                      </Field>
                      <Field label="Nombre">{(id) => <TextInput id={id} size="sm" required maxLength={80} value={d.label} onChange={(e) => setDoc(i, { label: e.target.value })} />}</Field>
                    </div>
                    <div className="grid gap-3 sm:grid-cols-3">
                      <Field label="Alcance">
                        {(id) => (
                          <SelectInput
                            id={id}
                            size="sm"
                            options={[
                              { value: "PROJECT", label: "Del proyecto" },
                              { value: "ISSUE", label: "De cada issue" },
                            ]}
                            value={d.scope}
                            onChange={(scope) => scope && setDoc(i, { scope })}
                          />
                        )}
                      </Field>
                      <Field label="Lo escribe">{(id) => <SelectInput id={id} size="sm" options={roleOptions} value={d.writtenBy ?? ""} onChange={(v) => setDoc(i, { writtenBy: v || null })} />}</Field>
                      <Field label="Cuándo">
                        {(id) => (
                          <SelectInput
                            id={id}
                            size="sm"
                            options={[
                              { value: "ALWAYS", label: "Siempre" },
                              { value: "SUBSTANTIAL", label: "Solo si es sustancial" },
                            ]}
                            value={d.condition}
                            onChange={(condition) => condition && setDoc(i, { condition })}
                          />
                        )}
                      </Field>
                    </div>
                    <Field label="Ruta en el repositorio" hint={d.scope === "ISSUE" ? "Lleva una vez {feature} (carpeta por issue) o {task} (un archivo) y termina en .md." : "Ruta fija que termina en .md."}>
                      {(id) => <TextInput id={id} size="sm" required value={d.pathPattern} onChange={(e) => setDoc(i, { pathPattern: e.target.value })} className="font-mono text-xs" />}
                    </Field>
                    <Field label="Para qué sirve">{(id) => <TextInput id={id} size="sm" value={d.purpose} onChange={(e) => setDoc(i, { purpose: e.target.value })} />}</Field>
                    <Field label="Plantilla" hint="Markdown. Marcadores: {project}, {key}, {title}, {date}.">
                      {(id) => <TextArea id={id} rows={6} value={d.template} onChange={(e) => setDoc(i, { template: e.target.value })} className="font-mono text-xs" />}
                    </Field>
                    {d.writtenBy && (
                      <Field label="Instrucciones para el agente">
                        {(id) => <TextArea id={id} rows={3} value={d.agentInstructions ?? ""} onChange={(e) => setDoc(i, { agentInstructions: e.target.value || null })} />}
                      </Field>
                    )}
                    <div className="flex items-center justify-between">
                      <label className="inline-flex items-center gap-2 text-[13px] text-fg">
                        <input type="checkbox" checked={d.required} onChange={(e) => setDoc(i, { required: e.target.checked })} className="size-4 accent-sol" />
                        Obligatorio
                      </label>
                      <Button
                        tone="ghost"
                        size="sm"
                        icon={Trash}
                        onClick={() => {
                          onChange({ ...value, documents: value.documents.filter((_, j) => j !== i) });
                          setOpenDoc(null);
                        }}
                      >
                        Quitar documento
                      </Button>
                    </div>
                  </div>
                )}
              </li>
            );
          })}
          {value.documents.length === 0 && <li className="px-3 py-3 text-xs text-fg-faint">Sin documentos: solo el tablero.</li>}
        </ul>
      </Group>
    </div>
  );
}

// ---- Rol de agente ----

export function RoleEditor({ value, onChange, isNew }: { value: AgentRole; onChange: (v: AgentRole) => void; isNew: boolean }) {
  return (
    <div className="flex flex-col gap-5">
      <Identity value={value} onChange={onChange} isNew={isNew} keyHint="Ej. MOBILE o DATA_ENGINEER." />
      <Field label="Descripción">
        {(id) => <TextInput id={id} maxLength={300} value={value.description} onChange={(e) => onChange({ ...value, description: e.target.value })} />}
      </Field>
      <Field label="Instrucciones" hint="El prompt de sistema del rol.">
        {(id) => <TextArea id={id} rows={12} value={value.instructions} onChange={(e) => onChange({ ...value, instructions: e.target.value })} className="text-[13px] leading-relaxed" />}
      </Field>
      <LinesField label="Herramientas del agente" hint="Una por línea, ej. Read, Bash(./mvnw *), WebSearch. CodeExecution gasta más." value={value.tools} onChange={(tools) => onChange({ ...value, tools })} rows={4} mono />
      <div className="flex flex-col gap-1.5">
        <span className="text-xs font-medium text-fg-muted">Automatización en el tablero</span>
        <ChoiceCards
          name="role-automation"
          label="Automatización"
          columns={2}
          value={value.automation}
          onChange={(automation) => onChange({ ...value, automation })}
          choices={[
            { value: "NONE", label: "Ninguna", hint: "Trabaja desde su entorno." },
            { value: "REFINE_ISSUE", label: "Refinar issues", hint: "Propone historia, criterios y estimación, y lo devuelve." },
          ]}
        />
      </div>
    </div>
  );
}

// ---- Servidor MCP ----

export function McpEditor({ value, onChange, isNew }: { value: McpServer; onChange: (v: McpServer) => void; isNew: boolean }) {
  const stdio = value.transport === "STDIO";
  return (
    <div className="flex flex-col gap-5">
      <Identity value={value} onChange={onChange} isNew={isNew} keyHint="Ej. POSTGRES o PLAYWRIGHT." />
      <Field label="Descripción">{(id) => <TextInput id={id} maxLength={300} value={value.description} onChange={(e) => onChange({ ...value, description: e.target.value })} />}</Field>
      <div className="flex flex-col gap-1.5">
        <span className="text-xs font-medium text-fg-muted">Transporte</span>
        <ChoiceCards
          name="mcp-transport"
          label="Transporte"
          columns={2}
          value={value.transport}
          onChange={(transport) => onChange({ ...value, transport })}
          choices={[
            { value: "STDIO", label: "Local (stdio)", hint: "Un proceso, ej. npx." },
            { value: "HTTP", label: "Remoto (HTTP)", hint: "Un servicio en una URL." },
          ]}
        />
      </div>
      {stdio ? (
        <div className="grid gap-3 sm:grid-cols-[10rem_minmax(0,1fr)]">
          <Field label="Comando">{(id) => <TextInput id={id} required value={value.command ?? ""} onChange={(e) => onChange({ ...value, command: e.target.value })} className="font-mono text-xs" />}</Field>
          <LinesField label="Argumentos" value={value.args} onChange={(args) => onChange({ ...value, args })} rows={3} mono />
        </div>
      ) : (
        <Field label="URL">{(id) => <TextInput id={id} required type="url" value={value.url ?? ""} onChange={(e) => onChange({ ...value, url: e.target.value })} className="font-mono text-xs" />}</Field>
      )}
      <Group
        title="Variables de entorno"
        description="Solo el nombre: el valor lo pone cada dev en su máquina."
        actions={
          <Button size="sm" icon={Plus} onClick={() => onChange({ ...value, envVars: [...value.envVars, { name: "", required: false, description: null }] })}>
            Variable
          </Button>
        }
      >
        {value.envVars.length === 0 ? (
          <p className="text-xs text-fg-faint">Sin variables.</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {value.envVars.map((v, i) => (
              <li key={i} className="grid grid-cols-[12rem_minmax(0,1fr)_auto_auto] items-center gap-2">
                <TextInput
                  size="sm"
                  required
                  aria-label="Nombre de la variable"
                  value={v.name}
                  onChange={(e) => onChange({ ...value, envVars: value.envVars.map((x, j) => (j === i ? { ...x, name: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, "_") } : x)) })}
                  className="font-mono text-xs"
                />
                <TextInput size="sm" aria-label="Para qué sirve" placeholder="Para qué sirve" value={v.description ?? ""} onChange={(e) => onChange({ ...value, envVars: value.envVars.map((x, j) => (j === i ? { ...x, description: e.target.value || null } : x)) })} />
                <label className="inline-flex items-center gap-1.5 text-xs text-fg">
                  <input type="checkbox" checked={v.required} onChange={(e) => onChange({ ...value, envVars: value.envVars.map((x, j) => (j === i ? { ...x, required: e.target.checked } : x)) })} className="size-4 accent-sol" />
                  Obligatoria
                </label>
                <Button tone="ghost" size="sm" icon={Trash} iconOnly label="Quitar variable" onClick={() => onChange({ ...value, envVars: value.envVars.filter((_, j) => j !== i) })} />
              </li>
            ))}
          </ul>
        )}
      </Group>
      <Field label="Documentación">
        {(id) => <TextInput id={id} type="url" value={value.docsUrl ?? ""} onChange={(e) => onChange({ ...value, docsUrl: e.target.value || null })} className="font-mono text-xs" />}
      </Field>
    </div>
  );
}

const COMPONENT_KINDS: { value: ComponentKind; label: string }[] = [
  { value: "BACKEND", label: "Backend / API" },
  { value: "FRONTEND", label: "Frontend" },
  { value: "MONOLITH", label: "Monolito (backend y pantallas)" },
  { value: "MICROFRONTEND", label: "Microfrontend" },
  { value: "SHELL", label: "Shell de microfrontends" },
  { value: "MOBILE", label: "Móvil" },
  { value: "LIBRARY", label: "Librería" },
  { value: "OTHER", label: "Otra" },
];

export const COMPONENT_KIND_LABEL = Object.fromEntries(COMPONENT_KINDS.map((k) => [k.value, k.label])) as Record<ComponentKind, string>;

/** Plantilla de pieza: su código base (repo y versión fija), variables, comandos y verificaciones de CI. */
export function ComponentEditor({ value, onChange, isNew }: { value: ComponentTemplate; onChange: (v: ComponentTemplate) => void; isNew: boolean }) {
  const cmd = (k: keyof ComponentTemplate["commands"], v: string) => onChange({ ...value, commands: { ...value.commands, [k]: v || null } });
  return (
    <div className="flex flex-col gap-5">
      <Identity value={value} onChange={onChange} isNew={isNew} keyHint="Ej. API_DOTNET o WEB_ANGULAR." />
      <Field label="Descripción">{(id) => <TextInput id={id} maxLength={300} value={value.description} onChange={(e) => onChange({ ...value, description: e.target.value })} />}</Field>
      <div className="grid gap-3 sm:grid-cols-[14rem_minmax(0,1fr)]">
        <Field label="Tipo de pieza">
          {(id) => <SelectInput id={id} options={COMPONENT_KINDS} value={value.kind} onChange={(kind) => kind && onChange({ ...value, kind })} />}
        </Field>
        <Field label="Stack">
          {(id) => <TextInput id={id} required maxLength={120} value={value.stack} onChange={(e) => onChange({ ...value, stack: e.target.value })} placeholder="Angular 20 · TypeScript" />}
        </Field>
      </div>
      <Group title="Código base" description="Vacío: la pieza nace solo con su README y sus comandos.">
        <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_10rem]">
          <Field label="Repo plantilla" hint="owner/repo. La app de GitHub tiene que poder leerlo.">
            {(id) => <TextInput id={id} value={value.templateRepo ?? ""} onChange={(e) => onChange({ ...value, templateRepo: e.target.value.trim() || null })} placeholder="mi-org/plantilla-api" className="font-mono text-xs" />}
          </Field>
          <Field label="Versión" hint="Etiqueta, rama o commit.">
            {(id) => <TextInput id={id} value={value.templateRef ?? ""} onChange={(e) => onChange({ ...value, templateRef: e.target.value.trim() || null })} placeholder="v1.0.0" className="font-mono text-xs" />}
          </Field>
        </div>
        <p className="text-xs text-fg-muted">
          Marcadores: <code className="font-mono">{"{{PROYECTO}}"}</code>, <code className="font-mono">{"{{PROYECTO_CLAVE}}"}</code>, <code className="font-mono">{"{{PIEZA}}"}</code>,{" "}
          <code className="font-mono">{"{{PIEZA_RUTA}}"}</code> (carpeta de trabajo del CI).
        </p>
      </Group>
      <Group
        title="Variables"
        actions={
          <Button size="sm" icon={Plus} onClick={() => onChange({ ...value, variables: [...value.variables, { name: "", description: null, defaultValue: null }] })}>
            Variable
          </Button>
        }
      >
        {value.variables.length === 0 ? (
          <p className="text-xs text-fg-faint">Sin variables propias.</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {value.variables.map((v, i) => {
              const set = (patch: Partial<ComponentTemplate["variables"][number]>) => onChange({ ...value, variables: value.variables.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
              return (
                <li key={i} className="grid grid-cols-[10rem_minmax(0,1fr)_minmax(0,1fr)_auto] items-center gap-2">
                  <TextInput size="sm" required aria-label="Nombre" value={v.name} onChange={(e) => set({ name: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, "_") })} className="font-mono text-xs" />
                  <TextInput size="sm" aria-label="Para qué sirve" placeholder="Para qué sirve" value={v.description ?? ""} onChange={(e) => set({ description: e.target.value || null })} />
                  <TextInput size="sm" aria-label="Valor por defecto" placeholder="Valor por defecto" value={v.defaultValue ?? ""} onChange={(e) => set({ defaultValue: e.target.value || null })} className="font-mono text-xs" />
                  <Button tone="ghost" size="sm" icon={Trash} iconOnly label="Quitar variable" onClick={() => onChange({ ...value, variables: value.variables.filter((_, j) => j !== i) })} />
                </li>
              );
            })}
          </ul>
        )}
      </Group>
      <Group title="Comandos">
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Compilar">{(id) => <TextInput id={id} value={value.commands.build ?? ""} onChange={(e) => cmd("build", e.target.value)} className="font-mono text-xs" />}</Field>
          <Field label="Probar">{(id) => <TextInput id={id} value={value.commands.test ?? ""} onChange={(e) => cmd("test", e.target.value)} className="font-mono text-xs" />}</Field>
          <Field label="Levantar">{(id) => <TextInput id={id} value={value.commands.run ?? ""} onChange={(e) => cmd("run", e.target.value)} className="font-mono text-xs" />}</Field>
        </div>
      </Group>
      <div className="grid gap-3 sm:grid-cols-3">
        <LinesField label="Verificaciones de CI" hint="Jobs que exigen las ramas protegidas." value={value.ciChecks} onChange={(ciChecks) => onChange({ ...value, ciChecks })} rows={3} mono />
        <LinesField label="Roles sugeridos" value={value.suggestedRoles} onChange={(suggestedRoles) => onChange({ ...value, suggestedRoles })} rows={3} mono />
        <LinesField label="Servidores MCP" value={value.mcpServers} onChange={(mcpServers) => onChange({ ...value, mcpServers })} rows={3} mono />
      </div>
    </div>
  );
}
