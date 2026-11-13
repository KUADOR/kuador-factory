"use client";

import { UserMinus } from "lucide-react";
import { useState, useTransition } from "react";

import { addBoardPersonAction, removeBoardPersonAction } from "@/app/actions";
import { useAccess } from "@/components/shell/AccessContext";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Field, SelectInput } from "@/components/ui/Field";
import { Badge } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import { ACCESS_LABEL } from "@/lib/access";
import type { BoardPerson } from "@/lib/types";

/** Personas del tablero. Quien administra suma y quita; el resto solo ve quién está (la API también lo impide). */
export function PeopleEditor({ projectKey, people: initial }: { projectKey: string; people: BoardPerson[] }) {
  const { canAdminister } = useAccess();
  const [people, setPeople] = useState(initial);
  const [pending, start] = useTransition();
  const inBoard = people.filter((p) => p.inBoard);
  const others = people.filter((p) => !p.inBoard);

  const act = (work: () => Promise<{ ok: true; data: BoardPerson[] } | { ok: false; error: string }>, done: string) =>
    start(async () => {
      const r = await work();
      if (!r.ok) {
        notify.error("No se pudo guardar", r.error);
        return;
      }
      setPeople(r.data);
      notify.ok(done);
    });

  return (
    <div className="flex flex-col gap-5">
      <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
        {inBoard.map((p) => (
          <li key={p.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
            <Avatar member={{ name: p.name, avatarColor: p.avatarColor, kind: "HUMAN" }} size="md" />
            <div className="min-w-0 flex-1">
              <p className="truncate text-[13px] font-medium text-fg-strong">{p.name}</p>
              <p className="truncate text-xs text-fg-faint">{p.role}</p>
            </div>
            <Badge tone={p.access === "VIEW" ? "neutral" : "sol"}>{ACCESS_LABEL[p.access]}</Badge>
            {p.implicit ? (
              <span className="text-xs text-fg-faint">Ve todos los tableros</span>
            ) : (
              canAdminister && (
                <Button
                  size="sm"
                  tone="ghost"
                  icon={UserMinus}
                  iconOnly
                  label={`Quitar a ${p.name} del tablero`}
                  disabled={pending}
                  onClick={() => act(() => removeBoardPersonAction(projectKey, p.id), `${p.name} ya no ve este tablero`)}
                />
              )
            )}
          </li>
        ))}
      </ul>

      {canAdminister && others.length > 0 && (
        <div className="w-72">
          <Field label="Sumar una persona del espacio">
            {(id) => (
              <SelectInput
                id={id}
                placeholder="Elegir persona…"
                value={null}
                disabled={pending}
                onChange={(v) => v && act(() => addBoardPersonAction(projectKey, v), "Persona sumada al tablero")}
                options={others.map((p) => ({ value: p.id, label: `${p.name} · ${ACCESS_LABEL[p.access]}` }))}
              />
            )}
          </Field>
        </div>
      )}
      {!canAdminister && <p className="text-[13px] text-fg-faint">Para sumar o quitar personas, pídeselo a quien administra el espacio.</p>}
    </div>
  );
}
