"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useState, useTransition } from "react";

import { addToTeamAction, createTeamAgentAction, removeFromTeamAction } from "@/app/actions";
import { AgentRow, CreateAgent, type Role } from "@/components/agents/AgentControls";
import { useAccess } from "@/components/shell/AccessContext";
import { Button } from "@/components/ui/Button";
import { Field, SelectInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { TeamAgent, WorkspaceAiStatus } from "@/lib/types";

/** Equipo de agentes del tablero: dónde trabaja cada uno, sumar uno del espacio o crear uno nuevo. */
export function TeamEditor({ projectKey, software, team: initial, roles, ai }: { projectKey: string; software: boolean; team: TeamAgent[]; roles: Role[]; ai: WorkspaceAiStatus }) {
  const { canAdminister } = useAccess();
  const [team, setTeam] = useState(initial);
  const [creating, setCreating] = useState(false);
  const [pending, start] = useTransition();
  const members = team.filter((a) => a.inTeam);
  const others = team.filter((a) => !a.inTeam);
  const serverWithoutKey = !ai.configured && members.some((a) => a.mode === "SERVER");

  const act = (work: () => Promise<{ ok: true; data: TeamAgent[] } | { ok: false; error: string }>, done?: string) =>
    start(async () => {
      const r = await work();
      if (!r.ok) {
        notify.error("No se pudo guardar", r.error);
        return;
      }
      setTeam(r.data);
      if (done) notify.ok(done);
    });

  return (
    <div className="flex flex-col gap-5">
      {serverWithoutKey && (
        <p role="status" className="text-[13px] text-warn">
          Falta la clave de IA de la empresa.{" "}
          <Link href="/connections?ver=ia" className="k-link">Configúrala en Conexiones → Inteligencia artificial</Link>.
        </p>
      )}

      {members.length === 0 ? (
        <p className="text-[13px] text-fg-faint">Este tablero no tiene agentes todavía.</p>
      ) : (
        <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
          {members.map((a) => (
            <AgentRow
              key={a.id}
              agent={a}
              models={ai.models}
              defaultModel={ai.defaultModel}
              disabled={pending}
              canConfigure={canAdminister}
              onChange={(next) => setTeam((t) => t.map((x) => (x.id === next.id ? { ...next, inTeam: true } : x)))}
              onRemove={() => act(() => removeFromTeamAction(projectKey, a.id), `${a.name} salió del equipo`)}
              projectKey={projectKey}
            />
          ))}
        </ul>
      )}

      <div className="flex flex-wrap items-end gap-3">
        {others.length > 0 && (
          <div className="w-72">
            <Field label="Sumar un agente del espacio">
              {(id) => (
                <SelectInput
                  id={id}
                  placeholder="Elegir agente…"
                  value={null}
                  disabled={pending}
                  onChange={(v) => v && act(() => addToTeamAction(projectKey, v), "Agente sumado al equipo")}
                  options={others.map((a) => ({ value: a.id, label: `${a.name} · ${a.roleName}` }))}
                />
              )}
            </Field>
          </div>
        )}
        {!creating && canAdminister && (
          <Button icon={Plus} onClick={() => setCreating(true)}>
            Crear agente
          </Button>
        )}
      </div>

      {creating && (
        <CreateAgent
          roles={roles}
          models={ai.models}
          defaultMode={software ? "LOCAL" : "SERVER"}
          pending={pending}
          onCancel={() => setCreating(false)}
          onCreate={(input) =>
            start(async () => {
              const r = await createTeamAgentAction(projectKey, input);
              if (!r.ok) {
                notify.error("No se pudo crear el agente", r.error);
                return;
              }
              setTeam(r.data);
              setCreating(false);
              notify.ok("Agente creado y sumado al equipo");
            })
          }
        />
      )}
    </div>
  );
}

