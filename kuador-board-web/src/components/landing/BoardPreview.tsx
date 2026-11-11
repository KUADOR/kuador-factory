/**
 * Vista del tablero para la presentación: columnas por etapa, agentes trabajando, una tarea que espera a otra y una
 * puerta que aprueba una persona. Datos de ejemplo, no de un cliente.
 */
type Card = { key: string; title: string; chips?: { text: string; tone: "role" | "wait" | "epic" | "gate" }[]; working?: string; who?: string };

const COLUMNS: { name: string; note: string; cards: Card[] }[] = [
  {
    name: "Refinamiento",
    note: "Product Owner",
    cards: [{ key: "CLI-21", title: "Recordatorios por WhatsApp", working: "Agente PO escribiendo la spec" }],
  },
  {
    name: "Desarrollo",
    note: "Backend · Frontend · DevOps",
    cards: [
      { key: "CLI-14", title: "API de citas", chips: [{ text: "Agenda", tone: "epic" }, { text: "Backend", tone: "role" }], working: "Agente Backend trabajando" },
      { key: "CLI-15", title: "Pantalla de agenda", chips: [{ text: "Agenda", tone: "epic" }, { text: "Espera a CLI-14", tone: "wait" }] },
      { key: "CLI-16", title: "Pipeline y contenedores", chips: [{ text: "DevOps", tone: "role" }], working: "Agente DevOps trabajando" },
    ],
  },
  {
    name: "Revisión de PR",
    note: "Aprueba una persona",
    cards: [{ key: "CLI-12", title: "Alta de pacientes", chips: [{ text: "Por aprobar", tone: "gate" }], who: "D" }],
  },
];

const TONE = {
  role: "bg-[#8fb4ff]/12 text-[#8fb4ff]",
  wait: "bg-[#ff8a4c]/12 text-[#ff8a4c]",
  epic: "bg-[#c4a7ff]/12 text-[#c4a7ff]",
  gate: "bg-[#ffb81c]/14 text-[#ffd66b]",
};

export function BoardPreview() {
  return (
    <div
      role="img"
      aria-label="Ejemplo de tablero: un agente PO escribe una spec, los agentes de backend y DevOps trabajan en paralelo, el frontend espera al backend y un PR espera la aprobación de una persona."
      className="overflow-hidden rounded-xl border border-line-strong bg-[#0d1219] shadow-[0_24px_80px_-24px_rgb(0_0_0/0.8)]"
    >
      <div className="flex items-center gap-2 border-b border-line px-4 py-2.5">
        <span className="size-2 rounded-full bg-[#2a3644]" />
        <span className="size-2 rounded-full bg-[#2a3644]" />
        <span className="size-2 rounded-full bg-[#2a3644]" />
        <span className="ml-3 font-mono text-[11px] text-fg-faint">go.kuador.com/projects/CLI</span>
      </div>
      <div className="grid grid-cols-2 gap-2.5 p-3 sm:grid-cols-3 sm:gap-3 sm:p-4">
        {COLUMNS.map((col, i) => (
          // En pantallas chicas caben dos columnas: la tercera (la puerta) aparece desde sm.
          <div key={col.name} className={i === 2 ? "hidden min-w-0 flex-col gap-2 sm:flex" : "flex min-w-0 flex-col gap-2"}>
            <div className="px-1">
              <p className="truncate text-[12px] font-semibold text-fg-strong">{col.name}</p>
              <p className="truncate text-[10px] text-fg-faint">{col.note}</p>
            </div>
            {col.cards.map((c) => (
              <div key={c.key} className="rounded-md border border-line-strong bg-[#151c26] px-2.5 py-2">
                <p className="text-[12px] leading-snug text-fg-strong">{c.title}</p>
                {c.chips && (
                  <div className="mt-1.5 flex flex-wrap gap-1">
                    {c.chips.map((chip) => (
                      <span key={chip.text} className={`rounded px-1.5 py-px text-[10px] font-medium ${TONE[chip.tone]}`}>
                        {chip.text}
                      </span>
                    ))}
                  </div>
                )}
                {c.working && (
                  <p className="mt-1.5 flex items-center gap-1.5 truncate text-[10px] text-[#34d399]">
                    <span className="size-1.5 shrink-0 rounded-full bg-[#34d399] motion-safe:animate-pulse" />
                    {c.working}
                  </p>
                )}
                <div className="mt-1.5 flex items-center justify-between">
                  <span className="font-mono text-[10px] text-fg-muted">{c.key}</span>
                  {c.who && <span className="grid size-4 place-items-center rounded-full bg-[#8fb4ff] text-[8px] font-semibold text-[#070a0f]">{c.who}</span>}
                </div>
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
