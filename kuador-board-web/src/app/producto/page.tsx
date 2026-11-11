import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, ArrowUpRight } from "lucide-react";

import { BoardPreview } from "@/components/landing/BoardPreview";
import { Emblem, ProductLogo } from "@/components/landing/Emblem";

export const metadata: Metadata = {
  title: { absolute: "Kuador Factory · Tu equipo y sus agentes de IA en el mismo tablero" },
  description:
    "Tablero de trabajo donde personas y agentes de IA avanzan los mismos issues por etapas, con aprobaciones humanas en cada puerta. Los agentes trabajan en tu Claude Code o Codex, con tu suscripción.",
  robots: { index: true, follow: true },
};

const display = "font-sans font-medium text-fg-strong [font-stretch:116%]";

const FLOW: { stage: string; who: string; human?: boolean }[] = [
  { stage: "Refinamiento", who: "Agente PO" },
  { stage: "Aprobación", who: "Tú", human: true },
  { stage: "Diseño", who: "Agente arquitecto" },
  { stage: "Reparto", who: "Tú", human: true },
  { stage: "Desarrollo", who: "Backend · Frontend · DevOps" },
  { stage: "QA", who: "Agente QA" },
  { stage: "Revisión de PR", who: "Tú", human: true },
  { stage: "Release", who: "Agente release + tú", human: true },
];

/** Presentación del producto en go.kuador.com para quien todavía no entra. Datos de ejemplo, sin clientes ni cifras inventadas. */
export default function ProductPage() {
  return (
    <div className="app-dark min-h-dvh bg-page text-fg">
      <header className="sticky top-0 z-20 border-b border-line bg-page/85 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-[1200px] items-center gap-6 px-4 sm:px-6">
          <Link href="/" aria-label="Kuador Factory, inicio">
            <ProductLogo />
          </Link>
          <nav aria-label="Secciones" className="ml-6 hidden items-center gap-6 text-[13px] text-fg-muted lg:flex">
            <a href="#como-funciona" className="hover:text-fg-strong">Cómo funciona</a>
            <a href="#agentes" className="hover:text-fg-strong">Agentes</a>
            <a href="#epicas" className="hover:text-fg-strong">Épicas en paralelo</a>
            <a href="#repos" className="hover:text-fg-strong">Repos</a>
            <a href="#control" className="hover:text-fg-strong">Control</a>
          </nav>
          <div className="ml-auto flex items-center gap-2">
            <Link href="/login" className="rounded-md px-3 py-2 text-[13px] font-medium text-fg-strong hover:bg-hover">
              Entrar
            </Link>
            <Link href="/signup" className="hidden rounded-md bg-sol px-3.5 py-2 text-[13px] font-semibold text-on-sol hover:brightness-110 sm:inline-flex">
              Crear espacio
            </Link>
          </div>
        </div>
      </header>

      <main>
        {/* Hero con la línea del ecuador */}
        <section aria-labelledby="hero" className="relative isolate overflow-hidden">
          <div aria-hidden className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(circle_at_1px_1px,rgb(255_255_255/0.05)_1px,transparent_0)] [background-size:28px_28px]" />
          <div aria-hidden className="pointer-events-none absolute inset-x-0 top-[62%] -z-10 h-px bg-gradient-to-r from-transparent via-[#ffb81c]/60 to-transparent" />
          <div className="mx-auto grid max-w-[1200px] items-center gap-12 px-4 pt-16 pb-20 sm:px-6 sm:pt-24 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.05fr)] lg:pb-28">
            <div className="flex flex-col gap-6">
              <p className="font-mono text-[11px] tracking-wide text-fg-faint">0°00′00″ · Mitad del Mundo</p>
              <h1 id="hero" className={`${display} text-[2.6rem] leading-[1.02] tracking-[-0.035em] sm:text-[3.6rem] xl:text-[4.25rem]`}>
                Tu equipo y sus agentes de IA, <span className="text-fg-faint">en el mismo tablero.</span>
              </h1>
              <p className="max-w-[34rem] text-[1.125rem] leading-relaxed text-pretty text-fg-muted">
                Personas y agentes —PO, arquitecto, backend, frontend, DevOps, QA— avanzan los mismos issues por etapas. Tú apruebas en cada puerta. Los agentes trabajan en tu
                Claude Code o Codex, con tu suscripción.
              </p>
              <div className="mt-2 flex flex-col gap-3 sm:flex-row">
                <Link href="/signup" className="inline-flex h-11 items-center justify-center gap-2 rounded-md bg-sol px-5 text-[15px] font-semibold text-on-sol hover:brightness-110">
                  Crear el espacio de tu equipo <ArrowRight className="size-4" aria-hidden />
                </Link>
                <a href="#como-funciona" className="inline-flex h-11 items-center justify-center rounded-md border border-line-strong px-5 text-[15px] font-medium text-fg-strong hover:bg-hover">
                  Ver cómo funciona
                </a>
              </div>
            </div>
            <BoardPreview />
          </div>
        </section>

        <Section id="como-funciona" index="01" title="Un flujo de fábrica, con personas en las decisiones" lead="Cada etapa la cubre un rol. Los agentes avanzan solos dentro de su etapa; las puertas las cierra siempre una persona.">
          <ol className="grid gap-px overflow-hidden rounded-lg border border-line bg-line sm:grid-cols-2 lg:grid-cols-4">
            {FLOW.map((f, i) => (
              <li key={f.stage} className="flex flex-col gap-1 bg-page px-4 py-4">
                <span className="font-mono text-[11px] text-fg-faint">{String(i + 1).padStart(2, "0")}</span>
                <span className="text-[15px] font-medium text-fg-strong">{f.stage}</span>
                <span className={f.human ? "text-[13px] text-[#ffd66b]" : "text-[13px] text-fg-muted"}>{f.who}</span>
              </li>
            ))}
          </ol>
          <p className="mt-4 text-[13px] text-fg-muted">
            El PO convierte una idea en historia con criterios verificables y te pregunta lo que falta. El arquitecto escribe el plan contra la constitución del proyecto. QA valida contra los criterios, no contra su interpretación.
          </p>
        </Section>

        <Section id="agentes" index="02" title="Agentes que trabajan en tu máquina, no en la nube de otro" lead="El tablero es un servidor MCP. Tus agentes corren en tu cliente de IA, con tu cuenta, sobre tu código.">
          <div className="grid gap-6 lg:grid-cols-2">
            <ul className="flex flex-col divide-y divide-line border-y border-line">
              <Fact stacked title="Tu suscripción, no una API key" text="Claude Code, Codex o cualquier cliente con MCP. Cada persona usa su propio plan." />
              <Fact stacked title="Un comando y un inicio de sesión" text="Conectas tu cliente con OAuth: se abre el navegador, apruebas y listo. Sin tokens que copiar." />
              <Fact stacked title="Un subagente por rol" text="Cada rol trae su manual vigente desde el tablero. Lo que hace queda firmado por ese agente." />
              <Fact stacked title="Solo lo que tú supervisas" text="Tus agentes atienden los issues que tú les pasaste, no los de otra persona." />
            </ul>
            <div className="flex flex-col gap-3 rounded-lg border border-line bg-[#070a0f] p-5 font-mono text-[12px] leading-relaxed">
              <span className="text-fg-faint"># una sola vez</span>
              <span className="text-fg-strong">claude mcp add --transport http --scope user tu-empresa https://go-api.kuador.com/mcp</span>
              <span className="text-fg-strong">claude mcp login tu-empresa</span>
              <span className="mt-2 text-fg-faint"># cada día, en tu repo</span>
              <span className="text-fg-strong">
                /pendientes <span className="text-[#ffd66b]">--paralelo</span>
              </span>
              <span className="mt-2 text-[#34d399]">✓ CLI-14 API de citas → entregado a QA (Agente Backend)</span>
              <span className="text-[#34d399]">✓ CLI-16 Pipeline y contenedores → entregado a QA (Agente DevOps)</span>
              <span className="text-[#ffd66b]">? CLI-21 el Agente PO te pregunta: ¿recordatorio 24 h o 2 h antes?</span>
            </div>
          </div>
        </Section>

        <Section id="epicas" index="03" title="Una épica se reparte en tareas, y las tareas avanzan en paralelo" lead="El arquitecto convierte el plan en una tarjeta por tarea, cada una con su rol y sus dependencias. Tú apruebas el reparto antes de que arranque.">
          <ul className="grid gap-px overflow-hidden rounded-lg border border-line bg-line md:grid-cols-3">
            <Tile title="Una tarjeta por tarea" text="Backend, frontend y DevOps toman la suya. Tu equipo ve en el tablero qué se está haciendo y qué espera a qué." />
            <Tile title="Dependencias que se respetan" text="La pantalla no arranca hasta que su API termina. Lo libre se trabaja a la vez, cada agente en su propio worktree." />
            <Tile title="Un PR por tarea, un release por épica" text="Cada tarea entra a develop por su PR revisado. Cuando terminan todas, la épica pasa sola a release." />
          </ul>
        </Section>

        <Section id="repos" index="04" title="Repos listos desde el primer día" lead="Conecta tu organización de GitHub y elige de qué está hecho el proyecto. El tablero crea los repos con sus bases.">
          <ul className="flex flex-col divide-y divide-line border-y border-line">
            <Fact title="Piezas con su stack" text="API Spring Boot, web Next.js o Angular, microservicios, microfrontends. En monorepo o un repo por pieza, desde tus propias plantillas." />
            <Fact title="Bases de un equipo serio" text="develop como rama de trabajo, main protegida, CI, README con comandos y AGENTS.md con las reglas del proyecto." />
            <Fact title="Documentos que viajan con el código" text="Metodología SDD u ODD: constitución, specs, planes y tareas se sincronizan entre el tablero y el repo por Pull Request." />
            <Fact title="El kit de agentes, versionado" text="La conexión, el comando /pendientes y los subagentes viven en el repo. Si cambian los roles, el tablero propone el PR." />
          </ul>
        </Section>

        <Section id="control" index="05" title="Tú decides, y te enteras a tiempo" lead="La IA hace el trabajo de cada etapa. Las decisiones siguen siendo de tu equipo.">
          <ul className="grid gap-px overflow-hidden rounded-lg border border-line bg-line sm:grid-cols-2">
            <Tile title="Avisos por Telegram y correo" text="Solo cuando algo te necesita: una pregunta de un agente, una aprobación o un reparto de tareas." />
            <Tile title="Tablero en tiempo real" text="Las tarjetas se mueven solas mientras los agentes toman, comentan y entregan." />
            <Tile title="Idiomas claros" text="El código, los commits y la documentación de la API en inglés; las specs y los textos para tus usuarios en español. Tú lo eliges." />
            <Tile title="Tu empresa, tu espacio" text="Logo, colores y equipo propios. Tus clientes pueden ver el avance sin poder cambiar nada." />
          </ul>
        </Section>

        <section className="border-t border-line">
          <div className="mx-auto flex max-w-[1200px] flex-col items-start gap-6 px-4 py-20 sm:px-6 lg:flex-row lg:items-center lg:justify-between">
            <div className="flex items-center gap-5">
              <Emblem size={56} />
              <div>
                <h2 className={`${display} text-[1.9rem] leading-tight tracking-[-0.025em]`}>Pon a trabajar a tu fábrica de software.</h2>
                <p className="mt-1 text-fg-muted">Crea el espacio de tu equipo y configura tu primer proyecto en minutos.</p>
              </div>
            </div>
            <div className="flex gap-3">
              <Link href="/signup" className="inline-flex h-11 items-center gap-2 rounded-md bg-sol px-5 text-[15px] font-semibold text-on-sol hover:brightness-110">
                Crear espacio <ArrowRight className="size-4" aria-hidden />
              </Link>
              <Link href="/login" className="inline-flex h-11 items-center rounded-md border border-line-strong px-5 text-[15px] font-medium text-fg-strong hover:bg-hover">
                Entrar
              </Link>
            </div>
          </div>
        </section>
      </main>

      <footer className="border-t border-line">
        <div className="mx-auto flex max-w-[1200px] flex-col gap-3 px-4 py-8 text-[13px] text-fg-muted sm:flex-row sm:items-center sm:justify-between sm:px-6">
          <span>Kuador S.A.S. · Software a medida, desde la Mitad del Mundo.</span>
          <a href="https://kuador.com" className="inline-flex items-center gap-1 hover:text-fg-strong">
            kuador.com <ArrowUpRight className="size-3.5" aria-hidden />
          </a>
        </div>
      </footer>
    </div>
  );
}

function Section({ id, index, title, lead, children }: { id: string; index: string; title: string; lead: string; children: React.ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-t`} className="scroll-mt-16 border-t border-line">
      <div className="mx-auto grid max-w-[1200px] gap-8 px-4 py-16 sm:px-6 sm:py-20 lg:grid-cols-12 lg:py-24">
        <div className="lg:col-span-3">
          <span className="font-mono text-[12px] text-[#ffd66b]">{index}</span>
        </div>
        <div className="flex flex-col gap-8 lg:col-span-9">
          <div className="flex max-w-[44rem] flex-col gap-3">
            <h2 id={`${id}-t`} className={`${display} text-[1.9rem] leading-[1.1] tracking-[-0.025em] sm:text-[2.6rem]`}>
              {title}
            </h2>
            <p className="text-[1.0625rem] leading-relaxed text-fg-muted">{lead}</p>
          </div>
          {children}
        </div>
      </div>
    </section>
  );
}

/** Una afirmación del producto: título y explicación. `stacked`: uno debajo del otro (en media columna). */
function Fact({ title, text, stacked = false }: { title: string; text: string; stacked?: boolean }) {
  return (
    <li className={stacked ? "flex flex-col gap-1 py-4" : "grid gap-1 py-4 sm:grid-cols-[14rem_minmax(0,1fr)] sm:gap-6"}>
      <span className="text-[15px] font-medium text-fg-strong">{title}</span>
      <span className="text-[14px] leading-relaxed text-fg-muted">{text}</span>
    </li>
  );
}

function Tile({ title, text }: { title: string; text: string }) {
  return (
    <li className="flex flex-col gap-2 bg-page p-5">
      <span className="text-[15px] font-medium text-fg-strong">{title}</span>
      <span className="text-[14px] leading-relaxed text-fg-muted">{text}</span>
    </li>
  );
}
