"use client";

import { usePathname, useRouter } from "next/navigation";
import { useTransition } from "react";

import { chooseSubjectAction } from "@/app/actions";
import { SelectInput } from "@/components/ui/Field";
import type { Subject } from "@/lib/types";

/** Secciones que son de un proyecto a la vez: sus documentos, su repositorio y sus primeros pasos. */
const ONE_AT_A_TIME = /\/(docs|sync|setup)(\/|$)|\/settings\/(general|methodology|repository)(\/|$)/;

/**
 * En un tablero que atiende varios proyectos: cuál se mira. En el tablero filtra las tarjetas («Todos» las muestra
 * todas); en documentos y repositorio dice de cuál son. Se recuerda por tablero.
 */
export function SubjectPicker({ projectKey, subjects, chosen }: { projectKey: string; subjects: Subject[]; chosen: string | null }) {
  const path = usePathname();
  const router = useRouter();
  const [pending, start] = useTransition();
  if (subjects.length < 2) return null;
  const one = ONE_AT_A_TIME.test(path);
  const options = [...(one ? [] : [{ value: "", label: "Todos los proyectos" }]), ...subjects.map((s) => ({ value: s.key, label: s.name }))];
  return (
    <div className="w-48">
      <SelectInput
        size="sm"
        ariaLabel="Proyecto"
        disabled={pending}
        options={options}
        // Sin elegir, lo que es de un proyecto a la vez muestra el primero (el mismo que usa la API).
        value={chosen ?? (one ? subjects[0].key : "")}
        onChange={(v) =>
          start(async () => {
            await chooseSubjectAction(projectKey, v || null);
            router.refresh();
          })
        }
      />
    </div>
  );
}
