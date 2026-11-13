"use client";

import { useState, useTransition } from "react";

import { setMemberAccessAction } from "@/app/actions";
import { useAccess } from "@/components/shell/AccessContext";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { notify } from "@/components/ui/Toaster";
import { ACCESS_HINT, ACCESS_LABEL, canManage } from "@/lib/access";
import type { Me, MemberAccess } from "@/lib/types";

const ORDER: MemberAccess[] = ["OWNER", "ADMIN", "EDIT", "VIEW"];

/** Los niveles que {@code actor} puede dar: quien administra, editar o mirar; el dueño, todos. */
export function accessChoices(actor: Pick<Me, "access"> | null) {
  return ORDER.filter((level) => canManage(actor, level)).map((value) => ({ value, label: ACCESS_LABEL[value], hint: ACCESS_HINT[value] }));
}

/** Nivel de acceso de una persona. Nadie cambia el suyo y solo el dueño toca administradores y dueños (la API también lo impide). */
export function MemberAccessPicker({ memberId, value, self }: { memberId: string; value: MemberAccess; self: boolean }) {
  const { me } = useAccess();
  const [access, setAccess] = useState(value);
  const [pending, start] = useTransition();
  if (self) return <p className="text-[13px] text-fg">{ACCESS_LABEL[value]} · otra persona del equipo puede cambiarlo.</p>;
  if (!canManage(me, value)) return <p className="text-[13px] text-fg">{ACCESS_LABEL[value]} · solo el dueño del espacio puede cambiarlo.</p>;
  return (
    <ChoiceCards
      name={`access-${memberId}`}
      label="Nivel de acceso"
      columns={2}
      value={access}
      disabled={pending}
      onChange={(next) =>
        start(async () => {
          const previous = access;
          setAccess(next);
          const r = await setMemberAccessAction(memberId, next);
          if (r.ok) notify.ok(`Acceso: ${ACCESS_LABEL[next].toLowerCase()}`);
          else {
            setAccess(previous);
            notify.error("No se pudo cambiar el acceso", r.error);
          }
        })
      }
      choices={accessChoices(me)}
    />
  );
}
