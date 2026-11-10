"use client";

import { useState, useTransition } from "react";

import { setMemberAccessAction } from "@/app/actions";
import { ChoiceCards } from "@/components/ui/ChoiceCards";
import { notify } from "@/components/ui/Toaster";
import { ACCESS_LABEL } from "@/lib/access";
import type { MemberAccess } from "@/lib/types";

export const ACCESS_CHOICES: { value: MemberAccess; label: string; hint: string }[] = [
  { value: "EDIT", label: ACCESS_LABEL.EDIT, hint: "Crea, mueve y comenta issues y documentos." },
  { value: "VIEW", label: ACCESS_LABEL.VIEW, hint: "Ve el avance. Aprueba solo en las puertas de su rol." },
];

/** Editar o solo mirar. Nadie cambia el suyo (la API también lo impide). */
export function MemberAccessPicker({ memberId, value, self }: { memberId: string; value: MemberAccess; self: boolean }) {
  const [access, setAccess] = useState(value);
  const [pending, start] = useTransition();
  if (self) return <p className="text-[13px] text-fg">{ACCESS_LABEL[value]} · otra persona del equipo puede cambiarlo.</p>;
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
      choices={ACCESS_CHOICES}
    />
  );
}
