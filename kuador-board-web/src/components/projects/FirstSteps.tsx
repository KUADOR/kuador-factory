import { Plus, Sparkles } from "lucide-react";

import { Button } from "@/components/ui/Button";

/** Empresa sin tableros: tres pasos para arrancar, sin más explicaciones. Quien administra empieza contándole su negocio a la IA. */
export function FirstSteps({ canDesign }: { canDesign: boolean }) {
  const steps = [
    {
      title: "Crea tu primer tablero",
      text: canDesign ? "Cuenta a qué se dedica tu empresa y te lo armamos, o parte de una plantilla." : "Desde una plantilla: ventas, marketing, tareas o software.",
      action: (
        <span className="flex flex-wrap gap-2">
          {canDesign && (
            <Button tone="primary" size="sm" icon={Sparkles} href="/design">
              Armarlo con IA
            </Button>
          )}
          <Button tone={canDesign ? "secondary" : "primary"} size="sm" icon={Plus} href="/projects?nuevo=1">
            {canDesign ? "Desde una plantilla" : "Nuevo tablero"}
          </Button>
        </span>
      ),
    },
    {
      title: "Conecta la IA de la empresa",
      text: "Para que tus agentes trabajen en el tablero.",
      action: (
        <Button size="sm" href="/connections">
          Ir a Conexiones
        </Button>
      ),
    },
    {
      title: "Invita a tu equipo",
      text: "Cada persona con su acceso.",
      action: (
        <Button size="sm" href="/team">
          Ir a Equipo
        </Button>
      ),
    },
  ];
  return (
    <div className="max-w-2xl">
      <h2 className="text-base font-semibold text-fg-strong">Empecemos</h2>
      <ol className="mt-4 divide-y divide-line rounded-lg border border-line bg-surface">
        {steps.map((s, i) => (
          <li key={s.title} className="flex flex-wrap items-center gap-4 px-5 py-4">
            <span className="tabular grid size-6 shrink-0 place-items-center rounded-full border border-line-strong font-mono text-[11px] text-fg-muted">{i + 1}</span>
            <div className="min-w-0 flex-1">
              <p className="text-[13px] font-medium text-fg-strong">{s.title}</p>
              <p className="text-xs text-fg-muted">{s.text}</p>
            </div>
            {s.action}
          </li>
        ))}
      </ol>
    </div>
  );
}
