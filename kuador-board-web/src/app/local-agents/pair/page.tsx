import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { requireEditor } from "@/lib/require-editor";

import { PairForm } from "./PairForm";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Aprobar una máquina" };

/** Donde se aprueba el código que muestra el runner al instalarse (como al conectar una TV). */
export default async function PairPage({ searchParams }: { searchParams: Promise<{ code?: string }> }) {
  await requireEditor();
  const { code } = await searchParams;
  return (
    <>
      <PageHeader
        crumbs={[{ label: "Agentes locales", href: "/local-agents" }, { label: "Aprobar una máquina" }]}
        title="Aprobar una máquina"
        width="narrow"
        description="Escribe el código que muestra la terminal. La máquina queda conectada a tu nombre: sus agentes trabajan lo que supervisas y lo que el equipo deja libre en sus roles."
      />
      <PairForm initialCode={code ?? ""} />
    </>
  );
}
