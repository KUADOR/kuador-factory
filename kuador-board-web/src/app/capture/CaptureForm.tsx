"use client";

import { Send } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";

import { createIssueAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { DictateButton } from "@/components/ui/DictateButton";
import { Field, SelectInput, Switch, TextArea } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { IssueType, Project } from "@/lib/types";

const LAST_BOARD = "kuador.capture.board";

/** Primera etapa que trabaja un agente o una persona por rol (p. ej. Refinamiento o Investigación): ahí la tarjeta arranca sola. */
function firstWorkColumn(p: Project): string | null {
  for (const c of p.columns) {
    const st = p.stages.find((s) => s.column === c.key);
    if (st && !st.gate && st.roles.length > 0) return c.key;
  }
  return null;
}

function defaultType(p: Project): IssueType {
  const types = p.issueTypes.map((t) => t.type);
  return (types.includes("STORY") ? "STORY" : types.includes("TASK") ? "TASK" : types[0]) as IssueType;
}

/** La primera línea es el título; el resto, el detalle. */
function split(text: string): { title: string; description: string | null } {
  const lines = text.trim().split(/\r?\n/);
  let title = lines[0].trim();
  let rest = lines.slice(1).join("\n").trim();
  if (title.length > 120) {
    rest = (title + (rest ? "\n" + rest : "")).trim();
    title = title.slice(0, 117).trimEnd() + "…";
  }
  return { title, description: rest || null };
}

export function CaptureForm({ projects, initialText, initialBoard }: { projects: Project[]; initialText: string; initialBoard: string | null }) {
  const router = useRouter();
  const [text, setText] = useState(initialText);
  const [board, setBoard] = useState<string | null>(initialBoard ?? projects[0]?.key ?? null);
  const [agent, setAgent] = useState(true);
  const [pending, start] = useTransition();

  // El último tablero usado queda elegido (en este navegador).
  useEffect(() => {
    if (initialBoard) return;
    try {
      const last = localStorage.getItem(LAST_BOARD);
      // eslint-disable-next-line react-hooks/set-state-in-effect -- preferencia guardada en el navegador
      if (last && projects.some((p) => p.key === last)) setBoard(last);
    } catch {
      /* sin almacenamiento: queda el primero */
    }
  }, [initialBoard, projects]);

  const project = projects.find((p) => p.key === board) ?? null;
  const work = project ? firstWorkColumn(project) : null;
  const workName = project?.columns.find((c) => c.key === work)?.name;

  if (projects.length === 0) {
    return <p className="px-4 py-6 text-[13px] text-fg-muted sm:px-6">Primero crea un tablero.</p>;
  }

  const submit = () =>
    start(async () => {
      if (!project || !text.trim()) return;
      const { title, description } = split(text);
      const status = agent && work ? work : project.columns[0].key;
      const r = await createIssueAction(project.key, {
        title,
        description,
        type: defaultType(project),
        priority: "MEDIUM",
        storyPoints: null,
        assigneeId: null,
        parentKey: null,
        labels: [],
        status,
      });
      if (!r.ok) {
        notify.error("No se pudo crear", r.error);
        return;
      }
      try {
        localStorage.setItem(LAST_BOARD, project.key);
      } catch {
        /* sin almacenamiento */
      }
      notify.ok(`${r.data.key} creada`, agent && work ? `Arranca en «${workName}».` : undefined);
      setText("");
      router.refresh();
    });

  return (
    <form
      className="flex flex-col gap-4 px-4 py-5 sm:px-6"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <Field label="¿Qué hay que hacer?" hint="La primera línea es el título; lo demás, el detalle.">
        {(id) => (
          <div className="flex items-start gap-2">
            <TextArea
              id={id}
              rows={6}
              required
              autoFocus
              maxLength={8000}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="El cliente de la clínica quiere recordatorios por WhatsApp 24 h antes de la cita…"
            />
            <DictateButton onText={(t) => setText((x) => (x.trim() ? `${x.trimEnd()} ${t}` : t))} />
          </div>
        )}
      </Field>
      <Field label="Tablero">
        {(id) => <SelectInput id={id} value={board} onChange={setBoard} options={projects.map((p) => ({ value: p.key, label: `${p.key} · ${p.name}` }))} />}
      </Field>
      {work ? (
        <div className="flex items-start gap-3">
          <div className="min-w-0 flex-1">
            <p className="text-[13px] font-medium text-fg-strong">Que un agente la prepare</p>
            <p className="text-xs text-fg-muted">
              {agent ? `Entra en «${workName}».` : `Queda en «${project?.columns[0].name}».`}
            </p>
          </div>
          <Switch checked={agent} hideLabel label="Que un agente la prepare" onChange={setAgent} />
        </div>
      ) : null}
      <div className="flex items-center justify-between gap-3">
        <Link href="/decisions" className="k-link text-[13px]">
          Mis decisiones
        </Link>
        <Button type="submit" tone="primary" icon={Send} loading={pending} disabled={!text.trim() || !project}>
          Capturar
        </Button>
      </div>
    </form>
  );
}
