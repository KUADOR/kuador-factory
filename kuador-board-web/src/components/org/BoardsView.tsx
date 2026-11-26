"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { addBoardSubjectAction, assignTeamAction, removeBoardSubjectAction } from "@/app/actions";
import { CreateProjectForm } from "@/components/CreateProjectForm";
import { PageBody, PageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { ChipPicker } from "@/components/ui/ChipPicker";
import { Field, SelectInput } from "@/components/ui/Field";
import { SidePanel } from "@/components/ui/SidePanel";
import { notify } from "@/components/ui/Toaster";
import type { Project, SubjectListing, Team, WorkflowTemplate } from "@/lib/types";

/** Organización → Tableros: cada tablero une un flujo con el equipo que lo atiende y los proyectos que maneja. */
export function BoardsView({
  boards,
  teams,
  subjects,
  templates,
  recommended,
  initialOpen,
  creating: initialCreating,
}: {
  boards: Project[];
  teams: Team[];
  subjects: SubjectListing[];
  templates: WorkflowTemplate[];
  recommended: string[];
  initialOpen: string | null;
  creating: boolean;
}) {
  const [openKey, setOpenKey] = useState<string | null>(initialOpen);
  const [creating, setCreating] = useState(initialCreating);
  const opened = boards.find((b) => b.key === openKey) ?? null;
  const templateName = (key: string) => templates.find((t) => t.key === key)?.name ?? key;
  return (
    <>
      <PageHeader
        title="Tableros"
        description="Cómo se trabaja, qué equipo lo atiende y en qué proyectos."
        actions={
          <Button tone="primary" icon={Plus} onClick={() => setCreating(true)}>
            Nuevo tablero
          </Button>
        }
      />
      <PageBody>
        <ul className="divide-y divide-line overflow-hidden rounded-lg border border-line bg-surface">
          {boards.map((b) => (
            <li key={b.key}>
              <button type="button" onClick={() => setOpenKey(b.key)} className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3 text-left hover:bg-hover">
                <span className="w-16 shrink-0 font-mono text-[11px] text-fg-faint">{b.key}</span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-medium text-fg-strong">{b.name}</span>
                  <span className="text-xs text-fg-faint">{templateName(b.templateKey)}</span>
                </span>
                <span className="text-xs text-fg-muted">
                  {b.team?.name ?? "Sin equipo"} · {(b.subjects ?? []).map((s) => s.name).join(", ")}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </PageBody>

      <SidePanel open={opened != null} onClose={() => setOpenKey(null)} width="narrow" title={opened?.name ?? "Tablero"}>
        {opened && <BoardPanel key={opened.key} board={opened} teams={teams} subjects={subjects} />}
      </SidePanel>

      <SidePanel open={creating} onClose={() => setCreating(false)} width="narrow" title="Nuevo tablero">
        {creating && <CreateProjectForm templates={templates} recommended={recommended} subjects={subjects} teams={teams} onCancel={() => setCreating(false)} />}
      </SidePanel>
    </>
  );
}

function BoardPanel({ board, teams, subjects }: { board: Project; teams: Team[]; subjects: SubjectListing[] }) {
  const router = useRouter();
  const [team, setTeam] = useState(board.team?.id ?? null);
  const [chosen, setChosen] = useState((board.subjects ?? []).map((s) => s.key));
  const [pending, start] = useTransition();

  const changeTeam = (id: string) =>
    start(async () => {
      const r = await assignTeamAction(id, board.key);
      if (!r.ok) {
        notify.error("No se pudo cambiar el equipo", r.error);
        return;
      }
      setTeam(id);
      notify.ok(`Lo atiende ${r.data.name}`);
      router.refresh();
    });

  const changeSubjects = (next: string[]) =>
    start(async () => {
      const added = next.filter((k) => !chosen.includes(k));
      const removed = chosen.filter((k) => !next.includes(k));
      for (const k of added) {
        const r = await addBoardSubjectAction(board.key, { subjectKey: k });
        if (!r.ok) {
          notify.error("No se pudo sumar el proyecto", r.error);
          return;
        }
      }
      for (const k of removed) {
        const r = await removeBoardSubjectAction(board.key, k);
        if (!r.ok) {
          notify.error("No se pudo quitar el proyecto", r.error);
          return;
        }
      }
      setChosen(next);
      router.refresh();
    });

  return (
    <div className="flex flex-col gap-6 px-5 py-5">
      <Field label="Equipo que lo atiende">
        {(id) => (
          <SelectInput
            id={id}
            value={team}
            disabled={pending}
            onChange={(v) => v && v !== team && changeTeam(v)}
            options={teams.map((t) => ({ value: t.id, label: t.name }))}
          />
        )}
      </Field>
      <div className="flex flex-col gap-2">
        <span className="text-xs font-medium text-fg-muted">Proyectos</span>
        <ChipPicker
          value={chosen}
          options={subjects.map((s) => ({ value: s.key, label: s.name }))}
          onChange={(next) => next.length > 0 && changeSubjects(next)}
          placeholder="Sumar proyecto"
          ariaLabel="Proyectos del tablero"
        />
      </div>
      <p className="text-[13px] text-fg-muted">
        Sus pasos, la tarjeta y sus entradas, en{" "}
        <Link href={`/projects/${board.key}/settings`} className="k-link">
          la configuración del tablero
        </Link>
        .
      </p>
    </div>
  );
}
