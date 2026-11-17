"use client";

import { Lock } from "lucide-react";
import { useState } from "react";

import { Field, SelectInput, TextArea, TextInput } from "@/components/ui/Field";
import { EFFORT_LABEL, MODEL_LABEL } from "@/lib/labels";
import type { AgentPolicy, RoleTuning } from "@/lib/types";

import { Group, LinesField } from "./editors";

type Rules = AgentPolicy["rules"];
type Limits = AgentPolicy["limits"];
type Messages = AgentPolicy["messages"];
type Repo = AgentPolicy["repo"];

const TABS = [
  { id: "rules", label: "Reglas" },
  { id: "limits", label: "Límites" },
  { id: "messages", label: "Textos del tablero" },
  { id: "repo", label: "Archivos del repo" },
] as const;

/**
 * Reglas de agentes del espacio: lo que reciben todos los agentes además del manual de su rol. El núcleo de seguridad
 * se muestra aparte y no se edita. Un texto vacío vuelve al de fábrica.
 */
export function PolicyEditor({ value, onChange, core }: { value: AgentPolicy; onChange: (v: AgentPolicy) => void; core: string[] }) {
  const [tab, setTab] = useState<(typeof TABS)[number]["id"]>("rules");
  const rules = (patch: Partial<Rules>) => onChange({ ...value, rules: { ...value.rules, ...patch } });
  const limits = (patch: Partial<Limits>) => onChange({ ...value, limits: { ...value.limits, ...patch } });
  const messages = (patch: Partial<Messages>) => onChange({ ...value, messages: { ...value.messages, ...patch } });
  const repo = (patch: Partial<Repo>) => onChange({ ...value, repo: { ...value.repo, ...patch } });
  const tuning = (role: string, patch: Partial<RoleTuning>) => {
    const current = value.roles?.[role] ?? { model: null, effort: null };
    onChange({ ...value, roles: { ...(value.roles ?? {}), [role]: { ...current, ...patch } } });
  };

  return (
    <div className="flex flex-col gap-5">
      <p className="text-xs leading-relaxed text-fg-muted">
        Valen para todos los agentes del espacio: los que trabajan en el tablero, el PO y el Arquitecto, y los que trabajan en la PC de una persona
        (Claude Code, Codex). Los textos admiten variables entre llaves, como <code className="font-mono">{"{persona}"}</code>. Un texto vacío vuelve
        al de fábrica. Usa «Ver qué recibe» en Equipo de agentes para revisar el resultado en una tarjeta.
      </p>

      <div role="tablist" aria-label="Secciones" className="flex flex-wrap gap-1 border-b border-line">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            onClick={() => setTab(t.id)}
            className={`-mb-px border-b-2 px-3 py-2 text-[13px] ${tab === t.id ? "border-sol font-medium text-fg-strong" : "border-transparent text-fg-muted hover:text-fg-strong"}`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === "rules" && (
        <div className="flex flex-col gap-5">
          <div className="rounded-md border border-line bg-sunken px-3 py-2.5">
            <p className="flex items-center gap-1.5 text-xs font-medium text-fg-muted">
              <Lock className="size-3" aria-hidden /> Núcleo de seguridad (siempre va primero y no se edita)
            </p>
            <ul className="mt-1.5 list-disc pl-5 text-xs leading-relaxed text-fg">
              {core.map((r) => (
                <li key={r}>{r}</li>
              ))}
            </ul>
          </div>
          <LinesField label="Reglas comunes" hint="Todos los agentes. Una regla por línea." value={value.rules.common} onChange={(common) => rules({ common })} rows={5} />
          <LinesField
            label="Cómo preguntar"
            hint="Qué no volver a preguntar y qué no es una pregunta. Una regla por línea."
            value={value.rules.questions}
            onChange={(questions) => rules({ questions })}
            rows={5}
          />
          <LinesField
            label="Agentes que trabajan en el tablero"
            hint="Solo los que trabajan con la clave de IA de la empresa. Una regla por línea."
            value={value.rules.board}
            onChange={(board) => rules({ board })}
            rows={5}
          />
          <Group title="Según la tarjeta" description="Se suman solo cuando aplican.">
            <Text label="Épica: el plan crea sus tareas" value={value.rules.epicPlan} onChange={(epicPlan) => rules({ epicPlan })} />
            <Text label="Tarjeta demasiado grande" value={value.rules.bigCard} onChange={(bigCard) => rules({ bigCard })} />
            <Text label="Revisar el código en GitHub (QA en el tablero)" vars="{repos}, {clave}" value={value.rules.codeReview} onChange={(codeReview) => rules({ codeReview })} />
            <Text label="Devolver a la etapa anterior" vars="{columna}" value={value.rules.sendBack} onChange={(sendBack) => rules({ sendBack })} />
            <Text label="Búsqueda en Internet (roles con WebSearch)" value={value.rules.web} onChange={(web) => rules({ web })} />
          </Group>
          <Group title="PO y Arquitecto" description="Al refinar un issue o escribir el plan.">
            <Text label="El documento" value={value.rules.document} onChange={(document) => rules({ document })} />
            <Text label="Repartir una épica en tareas" value={value.rules.taskSplit} onChange={(taskSplit) => rules({ taskSplit })} />
            <Text label="Cómo llenar la propuesta" value={value.rules.proposalFields} onChange={(proposalFields) => rules({ proposalFields })} rows={9} />
          </Group>
          <Group title="Puertas que revisa un agente" description="Para el agente revisor cuando una etapa la cierra él (Configuración → Etapas).">
            <Text label="Cómo revisar la puerta" vars="{puerta}, {avanza}, {devolver}, {decidir}" value={value.rules.gateReview ?? ""} onChange={(gateReview) => rules({ gateReview })} rows={7} />
            <Text label="Con escalamiento: cuándo dejarla a una persona" vars="{aprobadores}, {sensibles}" value={value.rules.gateEscalate ?? ""} onChange={(gateEscalate) => rules({ gateEscalate })} />
            <Text label="Sin escalamiento: decide el agente" vars="{aprobadores}" value={value.rules.gateDecide ?? ""} onChange={(gateDecide) => rules({ gateDecide })} rows={2} />
          </Group>
        </div>
      )}

      {tab === "limits" && (
        <div className="grid gap-4 sm:grid-cols-2">
          <NumberField
            label="Vueltas de preguntas"
            hint="Si después de tantas vueltas respondidas el agente sigue preguntando, avanza y deja lo abierto como pendiente."
            value={value.limits.questionRounds}
            min={1}
            max={10}
            onChange={(questionRounds) => limits({ questionRounds })}
          />
          <NumberField label="Comentarios que lee" hint="Los más recientes de la tarjeta." value={value.limits.commentsRead} min={1} max={100} onChange={(commentsRead) => limits({ commentsRead })} />
          <NumberField label="Archivos que recibe" hint="Adjuntos de la tarjeta (hasta 40 MB en total)." value={value.limits.filesRead} min={1} max={30} onChange={(filesRead) => limits({ filesRead })} />
          <NumberField label="Búsquedas web por trabajo" hint="Búsquedas y páginas leídas, en los roles con WebSearch." value={value.limits.webSearches} min={1} max={30} onChange={(webSearches) => limits({ webSearches })} />
          <NumberField label="Vueltas del modelo con Claude" hint="Más vueltas = trabajos más largos y más costo." value={value.limits.turnsClaude} min={1} max={40} onChange={(turnsClaude) => limits({ turnsClaude })} />
          <NumberField label="Vueltas con otros proveedores" hint="OpenAI, DeepSeek y compatibles." value={value.limits.turnsOther} min={1} max={80} onChange={(turnsOther) => limits({ turnsOther })} />
          <div className="sm:col-span-2">
            <RoleTunings roles={value.roles ?? {}} onChange={tuning} />
          </div>
        </div>
      )}

      {tab === "messages" && (
        <div className="flex flex-col gap-4">
          <Text label="Devuelve para revisar" vars="{persona}" value={value.messages.returnForReview} onChange={(returnForReview) => messages({ returnForReview })} rows={2} />
          <Text label="Devuelve con preguntas" vars="{persona}" value={value.messages.returnWithQuestions} onChange={(returnWithQuestions) => messages({ returnWithQuestions })} rows={2} />
          <Text label="Pasó a una puerta de aprobación" vars="{columna}, {persona}" value={value.messages.toGate} onChange={(toGate) => messages({ toGate })} rows={2} />
          <Text label="Pasó a la etapa de otro agente" vars="{columna}" value={value.messages.toAgent} onChange={(toAgent) => messages({ toAgent })} rows={2} />
          <Text label="Pasó a una persona" vars="{columna}, {persona}" value={value.messages.toPerson} onChange={(toPerson) => messages({ toPerson })} rows={2} />
          <Text label="Título de las preguntas" value={value.messages.questionsTitle} onChange={(questionsTitle) => messages({ questionsTitle })} rows={1} />
          <Text label="Título de los pendientes" vars="{vueltas}" value={value.messages.pendingTitle} onChange={(pendingTitle) => messages({ pendingTitle })} rows={1} />
          <Text label="Enlace al documento" vars="{documento}, {criterios}" value={value.messages.readDocument} onChange={(readDocument) => messages({ readDocument })} rows={2} />
          <Text label="Debajo de una pregunta" value={value.messages.askFooter} onChange={(askFooter) => messages({ askFooter })} rows={2} />
          <Text label="No pudo empezar" vars="{motivo}" value={value.messages.failedStart} onChange={(failedStart) => messages({ failedStart })} rows={2} />
          <Text label="No pudo terminar" vars="{motivo}" value={value.messages.failedFinish} onChange={(failedFinish) => messages({ failedFinish })} rows={3} />
        </div>
      )}

      {tab === "repo" && (
        <div className="flex flex-col gap-4">
          <p className="text-xs leading-relaxed text-fg-muted">
            Se escriben al armar un repo y al actualizar el kit de agentes. Los cambios llegan a los repos existentes en el próximo PR del kit.
          </p>
          <Text label="AGENTS.md" vars="{empresa}, {servidor}, {reglas}" value={value.repo.agentsMd} onChange={(agentsMd) => repo({ agentsMd })} rows={14} mono />
          <Text label="Pasos de «/pendientes»" vars="{empresa}, {servidor}, {pedido}, {cada_issue}" value={value.repo.workSteps} onChange={(workSteps) => repo({ workSteps })} rows={12} mono />
          <Text label="Subagente de cada rol" vars="{rol}, {empresa}, {herramientas_tablero}, {clave_rol}, {reglas}" value={value.repo.subagent} onChange={(subagent) => repo({ subagent })} rows={12} mono />
          <Text label="Instrucciones del servidor MCP" vars="{empresa}" value={value.repo.mcpInstructions} onChange={(mcpInstructions) => repo({ mcpInstructions })} rows={3} />
          <Text label="README del proyecto" vars="{proyecto}, {clave}, {descripcion}, {piezas}, {documentos}" value={value.repo.readme} onChange={(readme) => repo({ readme })} rows={10} mono />
          <Text label="README de una pieza" vars="{proyecto}, {pieza}, {clave}, {stack}, {comandos}, {repo_documentos}" value={value.repo.pieceReadme} onChange={(pieceReadme) => repo({ pieceReadme })} rows={5} mono />
          <Text label="CI mínimo (.github/workflows/ci.yml)" vars="{archivos}" value={value.repo.ci} onChange={(ci) => repo({ ci })} rows={12} mono />
          <Text label=".gitignore" value={value.repo.gitignore} onChange={(gitignore) => repo({ gitignore })} rows={8} mono />
        </div>
      )}
    </div>
  );
}

function Text({ label, vars, value, onChange, rows = 3, mono }: { label: string; vars?: string; value: string; onChange: (v: string) => void; rows?: number; mono?: boolean }) {
  return (
    <Field label={label} hint={vars ? `Variables: ${vars}.` : undefined}>
      {(id) =>
        rows === 1 ? (
          <TextInput id={id} value={value ?? ""} onChange={(e) => onChange(e.target.value)} />
        ) : (
          <TextArea id={id} rows={rows} value={value ?? ""} onChange={(e) => onChange(e.target.value)} className={mono ? "font-mono text-xs" : "text-[13px] leading-relaxed"} />
        )
      }
    </Field>
  );
}

function NumberField({ label, hint, value, min, max, onChange }: { label: string; hint: string; value: number; min: number; max: number; onChange: (v: number) => void }) {
  return (
    <Field label={label} hint={`${hint} Entre ${min} y ${max}.`}>
      {(id) => <TextInput id={id} type="number" min={min} max={max} value={String(value)} onChange={(e) => onChange(Number(e.target.value) || 0)} className="tabular w-28" />}
    </Field>
  );
}

/**
 * Modelo y esfuerzo sugeridos por rol: el agente los usa si en su equipo nadie eligió otros. Es la perilla de costo más
 * grande: un modelo equilibrado con esfuerzo medio cuesta una fracción del más capaz al máximo.
 */
// Un vacío ("") elige a propósito el del espacio o del modelo; null toma lo de fábrica.
function RoleTunings({ roles, onChange }: { roles: Record<string, RoleTuning>; onChange: (role: string, patch: Partial<RoleTuning>) => void }) {
  const [adding, setAdding] = useState("");
  const keys = Object.keys(roles).sort();
  const modelOptions = [{ value: "DEFAULT", label: "El del espacio" }, ...Object.entries(MODEL_LABEL).map(([value, label]) => ({ value, label }))];
  const effortOptions = [{ value: "DEFAULT", label: "El del modelo" }, ...Object.entries(EFFORT_LABEL).map(([value, label]) => ({ value, label }))];
  return (
    <Group title="Modelo y esfuerzo por rol" description="Lo que usa cada rol si en el equipo nadie eligió otro modelo para su agente. Menos esfuerzo y modelos equilibrados gastan menos tokens; deja el más capaz para el diseño y lo difícil. Con un proveedor que no ofrece el modelo, se usa el del espacio.">
      <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
        {keys.map((role) => (
          <li key={role} className="grid gap-2 px-3 py-2 sm:grid-cols-[9rem_minmax(0,1fr)_minmax(0,14rem)] sm:items-center">
            <code className="font-mono text-xs text-fg-strong">{role}</code>
            <SelectInput ariaLabel={`Modelo de ${role}`} size="sm" value={roles[role].model || "DEFAULT"} onChange={(v) => onChange(role, { model: v && v !== "DEFAULT" ? v : "" })} options={modelOptions} />
            <SelectInput ariaLabel={`Esfuerzo de ${role}`} size="sm" value={roles[role].effort || "DEFAULT"} onChange={(v) => onChange(role, { effort: v && v !== "DEFAULT" ? v : "" })} options={effortOptions} />
          </li>
        ))}
      </ul>
      <div className="mt-2 flex items-center gap-2">
        <TextInput aria-label="Clave del rol" placeholder="Clave de otro rol, ej. SUPPORT" value={adding} maxLength={40} onChange={(e) => setAdding(e.target.value)} className="font-mono text-xs" />
        <button
          type="button"
          className="k-link shrink-0 text-xs"
          onClick={() => {
            const key = adding.trim().toUpperCase();
            if (key) onChange(key, {});
            setAdding("");
          }}
        >
          Agregar
        </button>
      </div>
    </Group>
  );
}

