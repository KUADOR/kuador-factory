"use client";

import { SectionNav } from "@/components/shell/SectionNav";
import { useAccess } from "@/components/shell/AccessContext";

/** Las dos caras del repositorio de un tablero: mantenerlo al día y armarlo. Sincronizar es de quien administra. */
export function RepoNav({ projectKey }: { projectKey: string }) {
  const { canAdminister } = useAccess();
  const base = `/projects/${projectKey}/sync`;
  const items = [...(canAdminister ? [{ href: base, label: "Sincronizar", exact: true }] : []), { href: `${base}/setup`, label: "Piezas y vínculo" }];
  return <SectionNav label="Repositorio" pickerLabel="Repositorio" groups={[{ items }]} />;
}
