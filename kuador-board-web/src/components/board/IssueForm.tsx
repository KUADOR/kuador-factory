"use client";

import { Sparkle } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Field, SelectInput, TextArea, TextInput, type SelectOption } from "@/components/ui/Field";
import { PriorityIcon, TypeIcon } from "@/components/ui/IssueIcons";
import { PRIORITY_LABEL, allowedTypes, parentLabel, typeLabel, type TypeSource } from "@/lib/labels";
import { PRIORITIES, type Issue, type IssueInput, type IssueStatus, type IssueType, type Member, type Priority, type Project } from "@/lib/types";

export interface IssueFormValues extends IssueInput {
  status: IssueStatus;
}

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
  // Foco en el título al abrir, solo con mouse o trackpad: en pantallas táctiles abriría el teclado.
  const titleRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (!readOnly && window.matchMedia("(pointer: fine)").matches) titleRef.current?.focus();
  }, [readOnly]);
  const set = <K extends keyof IssueFormValues>(key: K, value: IssueFormValues[K]) => setValues((v) => ({ ...v, [key]: value }));
  const dirty = JSON.stringify({ ...values, labels: labelsText }) !== JSON.stringify({ ...initial, labels: initial.labels.join(", ") });
  const newAgent = values.assigneeId !== initial.assigneeId ? members.find((m) => m.id === values.assigneeId && m.kind === "AGENT") : undefined;

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
          <TextInput
            id={id}
            required
            maxLength={255}
            ref={titleRef}
            value={values.title}
            onChange={(e) => set("title", e.target.value)}
            placeholder={readOnly ? undefined : "¿Qué hay que hacer?"}
            className="text-[15px] font-medium"
          />
        )}
      </Field>
      <Field label={software ? "Descripción y criterios de aceptación" : "Descripción"} hint="Admite Markdown.">
        {(id) => (
          <TextArea
            id={id}
            rows={layout === "detail" ? 7 : 5}
            value={values.description ?? ""}
            onChange={(e) => set("description", e.target.value)}
            placeholder={readOnly ? undefined : software ? "Como … quiero … para …\n\nDado …, cuando …, entonces …" : "Detalles, contacto, próximos pasos…"}
            className="leading-relaxed"
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
      <Field label="Responsable">
        {(id) => <SelectInput id={id} size="sm" disabled={readOnly} filter options={memberOptions} value={values.assigneeId ?? ""} onChange={(v) => set("assigneeId", v || null)} />}
      </Field>
      {newAgent && (
        <p className="-mt-1 flex gap-1.5 rounded-md bg-sol/10 px-2.5 py-2 text-[11px] leading-relaxed text-fg">
          <Sparkle className="mt-0.5 size-3 shrink-0 fill-sol text-sol" aria-hidden />
          <span>
            Al guardar, {newAgent.name} recibe el issue. Si su rol tiene automatización, comentará una propuesta y te lo devolverá para que lo apruebes.
          </span>
        </p>
      )}
      <Field label="Tipo">
        {(id) => <SelectInput id={id} size="sm" disabled={readOnly} options={typeOptions} value={values.type} onChange={(v) => v && set("type", v)} />}
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
          <aside aria-label="Propiedades" className="md:col-start-2 md:row-span-2 md:row-start-1 md:border-l md:border-line md:pl-5">
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
