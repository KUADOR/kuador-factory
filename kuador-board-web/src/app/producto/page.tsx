import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, ArrowUpRight } from "lucide-react";

import { BoardPreview } from "@/components/landing/BoardPreview";
import { Emblem, ProductLogo } from "@/components/landing/Emblem";

export const metadata: Metadata = {
  title: { absolute: "Kuador Factory · Tu fábrica de software con agentes de IA" },
  description:
    "Agentes de IA que refinan, diseñan, programan y prueban, en el mismo flujo que tu equipo. Tú decides en cada paso. Corren en tu Claude Code o Codex, con tu suscripción.",
  robots: { index: true, follow: true },
};

const display = "font-sans font-medium text-fg-strong [font-stretch:116%]";

const TEAM: { role: string; verb: string }[] = [
  { role: "Product Owner", verb: "Refina" },
  { role: "Arquitecto", verb: "Diseña" },
  { role: "Backend", verb: "Construye" },
  { role: "Frontend", verb: "Construye" },
  { role: "DevOps", verb: "Despliega" },
  { role: "QA", verb: "Verifica" },
  { role: "Release", verb: "Entrega" },
];

const FLOW: { stage: string; you?: boolean }[] = [
  { stage: "Spec" },
  { stage: "Apruebas", you: true },
  { stage: "Plan" },
  { stage: "Apruebas el reparto", you: true },
  { stage: "Código en paralelo" },
  { stage: "QA" },
  { stage: "Apruebas el PR", you: true },
  { stage: "Release" },
];

/** Presentación del producto en go.kuador.com para quien todavía no entra. Una idea por sección; datos de ejemplo, sin cifras inventadas. */
export default function ProductPage() {
  return (
    <div className="app-dark min-h-dvh bg-page text-fg">
      <header className="sticky top-0 z-20 border-b border-line/60 bg-page/80 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-[1200px] items-center gap-6 px-4 sm:px-6">
          <Link href="/" aria-label="Kuador Factory, inicio">
            <ProductLogo />
          </Link>
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
        {/* 1 · La idea: no es un tablero */}
        <section aria-labelledby="hero" className="relative isolate overflow-hidden">
          <div aria-hidden className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(circle_at_1px_1px,rgb(255_255_255/0.045)_1px,transparent_0)] [background-size:32px_32px] [mask-image:linear-gradient(to_bottom,black,transparent_85%)]" />
          <div className="mx-auto flex max-w-[1200px] flex-col items-center px-4 pt-24 text-center sm:px-6 sm:pt-36">
            <p className="font-mono text-[11px] tracking-wide text-fg-faint">0°00′00″ · Mitad del Mundo</p>
            <h1 id="hero" className={`${display} mt-8 max-w-[15ch] text-[2.75rem] leading-[1] tracking-[-0.04em] sm:text-[4.5rem] lg:text-[5.5rem]`}>
              <span className="text-fg-faint">No es un tablero.</span> Es tu fábrica de software.
            </h1>
            <p className="mt-8 max-w-[36rem] text-[1.125rem] leading-relaxed text-pretty text-fg-muted sm:text-[1.25rem]">
              Agentes de IA que refinan, diseñan, programan y prueban junto a tu equipo. Tú decides en cada paso.
            </p>
            <div className="mt-10 flex flex-col gap-3 sm:flex-row">
              <Link href="/signup" className="inline-flex h-12 items-center justify-center gap-2 rounded-md bg-sol px-6 text-[15px] font-semibold text-on-sol hover:brightness-110">
                Crear el espacio de tu equipo <ArrowRight className="size-4" aria-hidden />
              </Link>
              <a href="#equipo" className="inline-flex h-12 items-center justify-center rounded-md px-6 text-[15px] font-medium text-fg-strong hover:bg-hover">
                Cómo funciona
              </a>
            </div>
          </div>
          <div className="relative mx-auto mt-24 max-w-[1000px] px-4 pb-32 sm:px-6 sm:pb-40">
            <div aria-hidden className="absolute inset-x-0 top-1/2 -z-10 h-px bg-gradient-to-r from-transparent via-[#ffb81c]/50 to-transparent" />
            <BoardPreview />
          </div>
        </section>

        {/* 2 · Un equipo, no una lista de tareas */}
        <Chapter id="equipo" index="01" title="Un equipo completo, no una lista de tareas." lead="Cada etapa la cubre un agente con su rol y su manual. Trabajan los mismos issues que tus personas.">
          <ul className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-4 lg:grid-cols-7">
            {TEAM.map((t) => (
              <li key={t.role} className="flex flex-col gap-3 bg-page px-5 py-8">
                <span className="text-[13px] text-fg-muted">{t.role}</span>
                <span className={`${display} text-[1.5rem] tracking-[-0.02em]`}>{t.verb}</span>
              </li>
            ))}
          </ul>
        </Chapter>

        {/* 3 · Las decisiones siguen siendo tuyas */}
        <Chapter id="control" index="02" title="Tú tienes la última palabra." lead="Los agentes avanzan solos dentro de su etapa. Lo que importa —la spec, el reparto, el código que entra— lo apruebas tú.">
          <ol className="flex flex-wrap items-center gap-x-3 gap-y-4">
            {FLOW.map((f, i) => (
              <li key={f.stage} className="flex items-center gap-3">
                <span
                  className={
                    f.you
                      ? "rounded-full bg-sol px-4 py-2 text-[14px] font-semibold text-on-sol"
                      : "rounded-full border border-line-strong px-4 py-2 text-[14px] text-fg"
                  }
                >
                  {f.stage}
                </span>
                {i < FLOW.length - 1 && <ArrowRight className="size-4 text-fg-faint" aria-hidden />}
              </li>
            ))}
          </ol>
          <p className="text-[15px] text-fg-muted">Y te avisamos por Telegram o correo cuando algo te espera.</p>
        </Chapter>

        {/* 4 · Dónde corren */}
        <Chapter id="maquina" index="03" title="Corren en tu máquina, con tu suscripción." lead="Claude Code, Codex o cualquier cliente con MCP. Sin API keys, sobre tu código, en tus repos.">
          <div className="max-w-[44rem] rounded-xl border border-line bg-[#070a0f] p-6 font-mono text-[13px] leading-loose sm:p-8">
            <p className="text-fg-faint"># en tu repo</p>
            <p className="text-fg-strong">
              /pendientes <span className="text-[#ffd66b]">--paralelo</span>
            </p>
            <p className="mt-3 text-[#34d399]">✓ API de citas — lista para QA</p>
            <p className="text-[#34d399]">✓ Pipeline y contenedores — listo para QA</p>
            <p className="text-[#ffd66b]">? El PO te pregunta: ¿recordatorio 24 h o 2 h antes?</p>
          </div>
        </Chapter>

        {/* 5 · Cierre */}
        <section className="border-t border-line">
          <div className="mx-auto flex max-w-[1200px] flex-col items-center px-4 py-32 text-center sm:px-6 sm:py-44">
            <Emblem size={64} />
            <h2 className={`${display} mt-10 max-w-[18ch] text-[2.25rem] leading-[1.05] tracking-[-0.035em] sm:text-[3.5rem]`}>Pon a trabajar a tu fábrica.</h2>
            <p className="mt-6 max-w-[30rem] text-[1.0625rem] text-fg-muted">Crea el espacio de tu equipo y deja tu primer proyecto listo en minutos.</p>
            <div className="mt-10 flex flex-col gap-3 sm:flex-row">
              <Link href="/signup" className="inline-flex h-12 items-center justify-center gap-2 rounded-md bg-sol px-6 text-[15px] font-semibold text-on-sol hover:brightness-110">
                Crear espacio <ArrowRight className="size-4" aria-hidden />
              </Link>
              <Link href="/login" className="inline-flex h-12 items-center justify-center rounded-md border border-line-strong px-6 text-[15px] font-medium text-fg-strong hover:bg-hover">
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

/** Una sección = una idea: índice, titular grande, una frase y su prueba visual. */
function Chapter({ id, index, title, lead, children }: { id: string; index: string; title: string; lead: string; children: React.ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-t`} className="scroll-mt-16 border-t border-line">
      <div className="mx-auto flex max-w-[1200px] flex-col gap-14 px-4 py-28 sm:px-6 sm:py-40">
        <div className="flex max-w-[48rem] flex-col gap-6">
          <span className="font-mono text-[12px] text-[#ffd66b]">{index}</span>
          <h2 id={`${id}-t`} className={`${display} text-[2.25rem] leading-[1.05] tracking-[-0.035em] sm:text-[3.5rem]`}>
            {title}
          </h2>
          <p className="max-w-[38rem] text-[1.125rem] leading-relaxed text-fg-muted">{lead}</p>
        </div>
        {children}
      </div>
    </section>
  );
}
