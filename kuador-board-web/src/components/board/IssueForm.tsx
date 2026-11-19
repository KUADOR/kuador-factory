"use client";

import { Sparkle } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Field, SelectInput, TextArea, TextInput, type SelectOption } from "@/components/ui/Field";
import { MarkdownEditor } from "@/components/ui/MarkdownEditor";

import { issueTemplates } from "./issueTemplates";
import { firstStageAhead } from "./nextStep";
import { stageOf } from "./stages";
import { PriorityIcon, TypeIcon } from "@/components/ui/IssueIcons";
import { PRIORITY_LABEL, allowedTypes, parentLabel, typeLabel, type TypeSource } from "@/lib/labels";
import { PRIORITIES, type Issue, type IssueInput, type IssueStatus, type IssueType, type Member, type Priority, type Project } from "@/lib/types";

export interface IssueFormValues extends IssueInput {
  status: IssueStatus;
}

/** Un título que empieza con «Épica» (o «Epic»): la tarjeta nueva se crea como épica. */
const EPIC_TITLE = /^\s*[ÉéEe]pica?\b/i;

export function emptyValues(status: IssueStatus, projectType: TypeSource): IssueFormValues {
  return {
    title: "",
    description: null,
    // El primer tipo de trabajo de la plantilla que no sea épica (Historia en software, Oportunidad en ventas…).
    type: allowedTypes(projectType).find((t) => t !== "EPIC") ?? "TASK",
    priority: "MEDIUM",
    storyPoints: null,
    assigneeId: null,
    parentKey: null,
    labels: [],
    status,
  };
}

export function valuesOf(issue: Issue): IssueFormValues {
  const { title, description, type, priority, storyPoints, assigneeId, parentKey, labels, status } = issue;
  return { title, description, type, priority, storyPoints, assigneeId, parentKey, labels, status };
}

const CATEGORY_DOT = { TODO: "bg-fg-faint", IN_PROGRESS: "bg-info", DONE: "bg-ok" } as const;

/**
 * Formulario compartido para crear y editar. En «detail» el contenido va a la izquierda y las
 * propiedades en una columna a la derecha; en «stacked» todo va en una columna.
 * `below` se pinta bajo la descripción (comentarios, historial…).
 */
export function IssueForm({
  project,
  initial,
  members,
  epics,
  selfKey,
  layout,
  submitLabel,
  pending,
  error,
  onSubmit,
  meta,
  below,
  readOnly = false,
}: {
  project: Project;
  initial: IssueFormValues;
  members: Member[];
  epics: Issue[];
  selfKey?: string;
  layout: "detail" | "stacked";
  submitLabel: string;
  pending: boolean;
  error: string | null;
  onSubmit: (values: IssueFormValues) => void;
  /** Datos de solo lectura al pie de las propiedades (reportado por, fechas). */
  meta?: React.ReactNode;
  below?: React.ReactNode;
  /** Solo lectura: se ve todo, nada se edita ni se guarda. */
  readOnly?: boolean;
}) {
  const software = project.kind === "SOFTWARE";
  const [values, setValues] = useState(initial);
  const [labelsText, setLabelsText] = useState(initial.labels.join(", "));
  // Si el issue cambia mientras está abierto (otra persona, un agente), se toman los datos nuevos,
  // salvo que haya cambios sin guardar: esos no se pisan.
  const [base, setBase] = useState(initial);
  const formKey = (v: IssueFormValues, labels: string) => JSON.stringify({ ...v, labels });
  if (formKey(base, base.labels.join(", ")) !== formKey(initial, initial.labels.join(", "))) {
    if (formKey(values, labelsText) === formKey(base, base.labels.join(", "))) {
      setValues(initial);
      setLabelsText(initial.labels.join(", "));
    }
    setBase(initial);
  }
  // Foco en el título al abrir, solo con mouse o trackpad: en pantallas táctiles abriría el teclado.
  const titleRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (!readOnly && window.matchMedia("(pointer: fine)").matches) titleRef.current?.focus();
  }, [readOnly]);
  const set = <K extends keyof IssueFormValues>(key: K, value: IssueFormValues[K]) => setValues((v) => ({ ...v, [key]: value }));
  // En una tarjeta nueva, «Épica: …» en el título la crea como épica (MED-10 nació historia por el tipo por defecto),
  // salvo que la persona haya elegido el tipo a mano.
  const [typeTouched, setTypeTouched] = useState(false);
  const epicAllowed = allowedTypes(project).includes("EPIC");
  const setTitle = (title: string) =>
    setValues((v) => {
      if (selfKey || typeTouched || !epicAllowed) return { ...v, title };
      const epic = EPIC_TITLE.test(title);
      return { ...v, title, type: epic ? "EPIC" : v.type === "EPIC" ? initial.type : v.type };
    });
  const dirty = formKey(values, labelsText) !== formKey(base, base.labels.join(", "));
  const newAgent = values.assigneeId !== base.assigneeId ? members.find((m) => m.id === values.assigneeId && m.kind === "AGENT") : undefined;

  const typeOptions: SelectOption<IssueType>[] = allowedTypes(project).map((t) => ({
    value: t,
    label: typeLabel(t, project),
    leading: <TypeIcon type={t} projectType={project} />,
  }));
  const statusOptions: SelectOption<string>[] = project.columns.map((c) => ({
    value: c.key,
    label: c.name,
    leading: <span aria-hidden className={`size-2 rounded-full ${CATEGORY_DOT[c.category]}`} />,
  }));
  const priorityOptions: SelectOption<Priority>[] = PRIORITIES.map((p) => ({ value: p, label: PRIORITY_LABEL[p], leading: <PriorityIcon priority={p} /> }));
  const memberOptions: SelectOption<string>[] = [
    { value: "", label: "Sin asignar", leading: <Avatar member={null} size="xs" /> },
    ...members.map((m) => ({ value: m.id, label: m.name, leading: <Avatar member={m} size="xs" /> })),
  ];
  const epicOptions: SelectOption<string>[] = [
    { value: "", label: "Ninguna" },
    ...epics.filter((e) => e.key !== selfKey).map((e) => ({ value: e.key, label: `${e.key} · ${e.title}` })),
  ];

  const main = (
    <fieldset disabled={readOnly} className="flex min-w-0 flex-col gap-4">
      <Field label="Título">
        {(id) => (
          <TextArea
            id={id}
            required
            rows={1}
            maxLength={255}
            ref={titleRef}
            value={values.title}
            onChange={(e) => setTitle(e.target.value.replace(/\n/g, " "))}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                e.currentTarget.form?.requestSubmit();
              }
            }}
            placeholder={readOnly ? undefined : "¿Qué hay que hacer?"}
            className="resize-none text-[15px] font-medium [field-sizing:content]"
          />
        )}
      </Field>
      <Field label={software ? "Descripción y criterios de aceptación" : "Descripción"}>
        {(id) => (
          <MarkdownEditor
            id={id}
            value={values.description ?? ""}
            onChange={(v) => set("description", v)}
            readOnly={readOnly}
            startInView={layout === "detail"}
            rows={layout === "detail" ? 14 : 10}
            templates={issueTemplates(software, values.type)}
            placeholder={software ? "Como … quiero … para …\n\nDado …, cuando …, entonces …" : "Detalles, contacto, próximos pasos…"}
          />
        )}
      </Field>
    </fieldset>
  );

  const properties = (
    <fieldset disabled={readOnly} className="flex min-w-0 flex-col gap-3.5">
      <Field label="Estado">
        {(id) => <SelectInput id={id} size="sm" disabled={readOnly} options={statusOptions} value={values.status} onChange={(v) => v && set("status", v)} />}
      </Field>
      {!selfKey && <StartHint project={project} members={members} status={values.status} onPick={(status) => set("status", status)} />}
      <Field label="Responsable">
        {(id) => <SelectInput id={id} size="sm" disabled={readOnly} filter options={memberOptions} value={values.assigneeId ?? ""} onChange={(v) => set("assigneeId", v || null)} />}
      </Field>
      {newAgent && (
        <p className="-mt-1 flex gap-1.5 text-[11px] text-fg-muted">
          <Sparkle className="mt-0.5 size-3 shrink-0 fill-sol text-sol" aria-hidden />
          <span>Al guardar, {newAgent.name} recibe el issue y empieza a trabajarlo.</span>
        </p>
      )}
      <Field label="Tipo">
        {(id) => (
          <SelectInput
            id={id}
            size="sm"
            disabled={readOnly}
            options={typeOptions}
            value={values.type}
            onChange={(v) => {
              if (!v) return;
              setTypeTouched(true);
              set("type", v);
            }}
          />
        )}
      </Field>
      <Field label="Prioridad">
        {(id) => <SelectInput id={id} size="sm" disabled={readOnly} options={priorityOptions} value={values.priority} onChange={(v) => v && set("priority", v)} />}
      </Field>
      <div className="grid grid-cols-[5.5rem_minmax(0,1fr)] gap-3">
        <Field label="Puntos">
          {(id) => (
            <TextInput
              id={id}
              size="sm"
              type="number"
              inputMode="numeric"
              min={0}
              max={100}
              value={values.storyPoints ?? ""}
              onChange={(e) => set("storyPoints", e.target.value === "" ? null : Number(e.target.value))}
              className="tabular"
            />
          )}
        </Field>
        <Field label={parentLabel(project)}>
          {(id) => (
            <SelectInput
              id={id}
              size="sm"
              disabled={readOnly || values.type === "EPIC"}
              options={epicOptions}
              value={values.type === "EPIC" ? "" : (values.parentKey ?? "")}
              onChange={(v) => set("parentKey", v || null)}
            />
          )}
        </Field>
      </div>
      <Field label="Etiquetas" hint="Separadas por coma.">
        {(id) => <TextInput id={id} size="sm" value={labelsText} onChange={(e) => setLabelsText(e.target.value)} placeholder={readOnly ? undefined : "backend, seguridad"} />}
      </Field>
      {meta}
    </fieldset>
  );

  return (
    <form
      className="flex min-h-full flex-col"
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit({
          ...values,
          title: values.title.trim(),
          description: values.description?.trim() ? values.description : null,
          labels: labelsText.split(",").map((l) => l.trim()).filter(Boolean),
          parentKey: values.type === "EPIC" ? null : values.parentKey,
        });
      }}
    >
      {layout === "detail" ? (
        <div className="grid flex-1 gap-6 px-5 py-5 md:grid-cols-[minmax(0,1fr)_16rem] md:grid-rows-[auto_1fr]">
          {main}
          {/* En el celular, primero leer y conversar (comentarios); los campos para editar quedan al final. */}
          <aside aria-label="Propiedades" className="max-md:order-last max-md:border-t max-md:border-line max-md:pt-5 md:col-start-2 md:row-span-2 md:row-start-1 md:border-l md:border-line md:pl-5">
            {properties}
          </aside>
          {below && <div className="min-w-0 md:col-start-1">{below}</div>}
        </div>
      ) : (
        <div className="flex flex-1 flex-col gap-5 px-5 py-5">
          {main}
          {properties}
          {below}
        </div>
      )}
      {!readOnly && (
      <div className="sticky bottom-0 flex items-center gap-3 border-t border-line bg-surface px-5 py-3">
        {error ? (
          <p role="alert" className="min-w-0 flex-1 text-[13px] text-danger">
            {error}
          </p>
        ) : (
          <p className="min-w-0 flex-1 text-xs text-fg-faint">{selfKey ? (dirty ? "Cambios sin guardar." : "Sin cambios.") : null}</p>
        )}
        <Button type="submit" tone="primary" loading={pending} disabled={!!selfKey && !dirty}>
          {submitLabel}
        </Button>
      </div>
      )}
    </form>
  );
}

/**
 * Al crear: qué pasa con la tarjeta en la columna elegida. En una columna de espera (Backlog, Prospecto, Pendiente)
 * avisa que nadie la trabaja y ofrece crearla directo en la etapa que arranca el flujo.
 */
function StartHint({ project, members, status, onPick }: { project: Project; members: Member[]; status: string; onPick: (status: string) => void }) {
  const column = project.columns.find((c) => c.key === status);
  const stage = stageOf(project, status);
  const agentFor = (roles: string[]) => members.find((m) => m.kind === "AGENT" && roles.includes(m.role));
  let text: React.ReactNode = null;
  if (stage?.gate) text = "Queda esperando la aprobación de una persona.";
  else if (stage && stage.roles.length > 0) {
    const agent = agentFor(stage.roles);
    text = agent ? <>Al crearla la toma <b className="font-medium text-fg-strong">{agent.name}</b>.</> : "Queda por tomar en esta etapa.";
  } else if (column?.category === "TODO") {
    const ahead = firstStageAhead(project, status);
    if (ahead) {
      text = (
        <>
          En «{column.name}» nadie la trabaja.{" "}
          <button type="button" className="k-link" onClick={() => onPick(ahead.key)}>
            Crearla en «{ahead.name}»
          </button>
        </>
      );
    }
  }
  if (!text) return null;
  return <p className="-mt-2 text-[11px] text-fg-muted">{text}</p>;
}
