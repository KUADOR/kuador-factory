import type { Metadata } from "next";

import { InboxSection } from "../inbox/InboxSection";
import { RoutinesSection } from "../routines/RoutinesSection";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Entradas" };

/** Cómo entra el trabajo sin que nadie lo cree a mano: correos que se vuelven tarjetas y tarjetas que nacen a su hora. */
export default async function IntakePage({ params }: PageProps<"/projects/[key]/settings/intake">) {
  const projectKey = (await params).key.toUpperCase();
  return (
    <div className="flex flex-col gap-10">
      <div id="correo" className="scroll-mt-4">
        <InboxSection projectKey={projectKey} />
      </div>
      <div id="rutinas" className="scroll-mt-4">
        <RoutinesSection projectKey={projectKey} />
      </div>
    </div>
  );
}
