import type { Metadata } from "next";
import Link from "next/link";
import { ArrowRight, ArrowUpRight } from "lucide-react";

import { BoardPreview } from "@/components/landing/BoardPreview";
import { Emblem, ProductLogo } from "@/components/landing/Emblem";
import { PlanCard } from "@/components/billing/PlanCard";
import { boardApi } from "@/lib/api";
import type { Plan } from "@/lib/types";

export const metadata: Metadata = {
  title: { absolute: "Kuador Go · El tablero de tu empresa, con agentes de IA" },
  description:
    "Organiza ventas, operación, proyectos o software en un tablero donde personas y agentes de IA avanzan el mismo flujo. Tú apruebas lo que importa.",
  robots: { index: true, follow: true },
};

const display = "font-sans font-medium text-fg-strong [font-stretch:116%]";

/** Flujos de las plantillas reales del catálogo; cada espacio puede crear los suyos. */
const AREAS: { area: string; flow: string[]; note: string }[] = [
  { area: "Ventas", flow: ["Prospecto", "Contactado", "Propuesta", "Negociación", "Ganado"], note: "Cada oportunidad, con su responsable y su siguiente paso." },
  { area: "Operación y tareas", flow: ["Pendiente", "Haciendo", "Listo"], note: "El trabajo del día a día, sin hojas de cálculo ni chats perdidos." },
  { area: "Software", flow: ["Backlog", "Por hacer", "En progreso", "En QA", "Hecho"], note: "Historias, bugs y entregas de tu equipo de desarrollo." },
];

const AGENT_STEPS: { verb: string; text: string }[] = [
  { verb: "Toma", text: "la tarjeta que le toca según su rol." },
  { verb: "Trabaja", text: "siguiendo el manual que tú le escribes." },
  { verb: "Pregunta", text: "cuando algo no está claro, y te avisa." },
  { verb: "Entrega", text: "con un comentario de lo que hizo." },
];

const FLOW: { stage: string; you?: boolean }[] = [
  { stage: "Nueva tarjeta" },
  { stage: "El agente prepara" },
  { stage: "Apruebas", you: true },
  { stage: "El agente ejecuta" },
  { stage: "Revisas", you: true },
  { stage: "Listo" },
];

const TEAM: { role: string; verb: string }[] = [
  { role: "Product Owner", verb: "Refina" },
  { role: "Arquitecto", verb: "Diseña" },
  { role: "Backend", verb: "Construye" },
  { role: "Frontend", verb: "Construye" },
  { role: "DevOps", verb: "Despliega" },
  { role: "QA", verb: "Verifica" },
  { role: "Release", verb: "Entrega" },
];

/** Hoja de ruta: se presenta como "en camino", no como algo disponible. */
const NEXT: { area: string; text: string }[] = [
  { area: "Marketing", text: "Agentes que preparan contenido, campañas y publicaciones para que tú las apruebes." },
  { area: "Tareas manuales", text: "Reportes, seguimientos y carga de datos que hoy hace alguien a mano, convertidos en flujos." },
  { area: "Ventas", text: "Agentes que preparan propuestas y recuerdan cada seguimiento." },
];

/** Presentación del producto en go.kuador.com para quien todavía no entra. Una idea por sección; datos de ejemplo, sin cifras inventadas. */
export default async function ProductPage() {
  // Los planes viven en la base; si la API no responde, la página se muestra sin precios.
  const plans: Plan[] = await boardApi.plans().catch(() => []);
  return (
    <div className="app-dark min-h-dvh bg-page text-fg">
      <header className="sticky top-0 z-20 border-b border-line/60 bg-page/80 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-[1200px] items-center gap-6 px-4 sm:px-6">
          <Link href="/" aria-label="Kuador Go, inicio">
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
        {/* La idea: el tablero de la empresa, con agentes */}
        <section aria-labelledby="hero" className="relative isolate overflow-hidden">
          <div aria-hidden className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(circle_at_1px_1px,rgb(255_255_255/0.045)_1px,transparent_0)] [background-size:32px_32px] [mask-image:linear-gradient(to_bottom,black,transparent_85%)]" />
          <div className="mx-auto flex max-w-[1200px] flex-col items-center px-4 pt-24 text-center sm:px-6 sm:pt-36">
            <p className="font-mono text-[11px] tracking-wide text-fg-faint">0°00′00″ · Mitad del Mundo</p>
            <h1 id="hero" className={`${display} mt-8 max-w-[16ch] text-[2.75rem] leading-[1] tracking-[-0.04em] sm:text-[4.5rem] lg:text-[5.5rem]`}>
              <span className="text-fg-faint">No es un tablero más.</span> Es tu equipo, con IA.
            </h1>
            <p className="mt-8 max-w-[38rem] text-[1.125rem] leading-relaxed text-pretty text-fg-muted sm:text-[1.25rem]">
              Ventas, operación, proyectos o software: personas y agentes de IA avanzan el mismo flujo. Tú apruebas lo que importa.
            </p>
            <div className="mt-10 flex flex-col gap-3 sm:flex-row">
              <Link href="/signup" className="inline-flex h-12 items-center justify-center gap-2 rounded-md bg-sol px-6 text-[15px] font-semibold text-on-sol hover:brightness-110">
                Crear el espacio de tu empresa <ArrowRight className="size-4" aria-hidden />
              </Link>
              <a href="#tablero" className="inline-flex h-12 items-center justify-center rounded-md px-6 text-[15px] font-medium text-fg-strong hover:bg-hover">
                Cómo funciona
              </a>
            </div>
          </div>
          <div className="relative mx-auto mt-24 max-w-[1000px] px-4 pb-32 sm:px-6 sm:pb-40">
            <div aria-hidden className="absolute inset-x-0 top-1/2 -z-10 h-px bg-gradient-to-r from-transparent via-[#ffb81c]/50 to-transparent" />
            <BoardPreview />
          </div>
        </section>

        <Chapter id="tablero" index="01" title="Un tablero para todo tu trabajo." lead="Cada área con su propio flujo. Empieza con una plantilla o diseña el de tu operación: columnas, roles y quién aprueba.">
          <ul className="grid gap-px overflow-hidden rounded-xl border border-line bg-line lg:grid-cols-3">
            {AREAS.map((a) => (
              <li key={a.area} className="flex flex-col gap-6 bg-page p-8">
                <span className={`${display} text-[1.5rem] tracking-[-0.02em]`}>{a.area}</span>
                <FlowLine steps={a.flow} />
                <p className="mt-auto text-[14px] leading-relaxed text-fg-muted">{a.note}</p>
              </li>
            ))}
          </ul>
        </Chapter>

        <Chapter id="agentes" index="02" title="Agentes de IA que trabajan contigo." lead="Un agente es un compañero más del tablero. Le das un rol y un manual; él hace el resto, en las mismas tarjetas que tu equipo.">
          <ul className="grid grid-cols-1 gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-2 lg:grid-cols-4">
            {AGENT_STEPS.map((s) => (
              <li key={s.verb} className="flex flex-col gap-3 bg-page px-6 py-8">
                <span className={`${display} text-[1.5rem] tracking-[-0.02em]`}>{s.verb}</span>
                <span className="text-[14px] leading-relaxed text-fg-muted">{s.text}</span>
              </li>
            ))}
          </ul>
        </Chapter>

        <Chapter id="control" index="03" title="Tú tienes la última palabra." lead="Marca las columnas que necesitan tu visto bueno. Ahí el trabajo se detiene hasta que una persona aprueba.">
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

        {/* Premium: la fábrica de software */}
        <section id="fabrica" aria-labelledby="fabrica-t" className="scroll-mt-16 border-t border-line bg-[#070a0f]">
          <div className="mx-auto flex max-w-[1200px] flex-col gap-14 px-4 py-28 sm:px-6 sm:py-40">
            <div className="flex max-w-[48rem] flex-col gap-6">
              <span className="flex items-center gap-3 font-mono text-[12px] text-[#ffd66b]">
                04
                <span className="rounded-full border border-[#ffb81c]/40 px-2.5 py-0.5 font-sans text-[11px] font-semibold tracking-wide text-[#ffd66b]">Plan Factory</span>
              </span>
              <h2 id="fabrica-t" className={`${display} text-[2.25rem] leading-[1.05] tracking-[-0.035em] sm:text-[3.5rem]`}>
                Si haces software, tienes una fábrica.
              </h2>
              <p className="max-w-[38rem] text-[1.125rem] leading-relaxed text-fg-muted">
                Siete agentes cubren cada etapa, de la spec al release, sobre tus repos. Tú apruebas la spec, el reparto de tareas y cada PR.
              </p>
            </div>
            <ul className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-4 lg:grid-cols-7">
              {TEAM.map((t) => (
                <li key={t.role} className="flex flex-col gap-3 bg-page px-5 py-8">
                  <span className="text-[13px] text-fg-muted">{t.role}</span>
                  <span className={`${display} text-[1.5rem] tracking-[-0.02em]`}>{t.verb}</span>
                </li>
              ))}
            </ul>
            <div className="grid gap-8 lg:grid-cols-[1fr_1.2fr] lg:items-center">
              <p className="max-w-[30rem] text-[1.0625rem] leading-relaxed text-fg-muted">
                <span className="text-fg-strong">Corren en tu máquina, con tu suscripción.</span> Claude Code, Codex o cualquier cliente con MCP. Sin API keys, sobre tu código.
              </p>
              <div className="rounded-xl border border-line bg-page p-6 font-mono text-[13px] leading-loose sm:p-8">
                <p className="text-fg-faint"># en tu repo</p>
                <p className="text-fg-strong">
                  /pendientes <span className="text-[#ffd66b]">--paralelo</span>
                </p>
                <p className="mt-3 text-[#34d399]">✓ API de citas — lista para QA</p>
                <p className="text-[#34d399]">✓ Pipeline y contenedores — listo para QA</p>
                <p className="text-[#ffd66b]">? El PO te pregunta: ¿recordatorio 24 h o 2 h antes?</p>
              </div>
            </div>
          </div>
        </section>

        {plans.length > 0 && (
          <Chapter id="planes" index="05" title="Elige cómo empezar." lead="Empieza gratis y cambia de plan cuando lo necesites. Los planes de pago traen días de prueba.">
            <ul className={`grid gap-4 ${plans.length >= 3 ? "lg:grid-cols-3" : "md:grid-cols-2"}`}>
              {plans.map((p) => (
                <li key={p.code}>
                  <PlanCard
                    plan={p}
                    tone="landing"
                    action={
                      <Link
                        href={`/signup?plan=${encodeURIComponent(p.code)}`}
                        className={
                          p.highlighted
                            ? "inline-flex h-11 items-center justify-center gap-2 rounded-md bg-sol px-5 text-[14px] font-semibold text-on-sol hover:brightness-110"
                            : "inline-flex h-11 items-center justify-center rounded-md border border-line-strong px-5 text-[14px] font-medium text-fg-strong hover:bg-hover"
                        }
                      >
                        {p.priceMonthly === 0 ? "Empezar gratis" : p.trialDays > 0 ? "Probar " + p.name : "Elegir " + p.name}
                      </Link>
                    }
                  />
                </li>
              ))}
            </ul>
          </Chapter>
        )}

        <Chapter id="en-camino" index={plans.length > 0 ? "06" : "05"} title="Lo que viene." lead="Software es el primer flujo que llevamos completo. Siguen otros, con agentes listos de fábrica.">
          <ul className="grid gap-px overflow-hidden rounded-xl border border-line bg-line lg:grid-cols-3">
            {NEXT.map((n) => (
              <li key={n.area} className="flex flex-col gap-4 bg-page p-8">
                <span className="w-fit rounded-full border border-line-strong px-2.5 py-0.5 text-[11px] font-medium text-fg-muted">En camino</span>
                <span className={`${display} text-[1.5rem] tracking-[-0.02em]`}>{n.area}</span>
                <p className="text-[14px] leading-relaxed text-fg-muted">{n.text}</p>
              </li>
            ))}
          </ul>
        </Chapter>

        <section className="border-t border-line">
          <div className="mx-auto flex max-w-[1200px] flex-col items-center px-4 py-32 text-center sm:px-6 sm:py-44">
            <Emblem size={64} />
            <h2 className={`${display} mt-10 max-w-[18ch] text-[2.25rem] leading-[1.05] tracking-[-0.035em] sm:text-[3.5rem]`}>Pon a trabajar a tu equipo.</h2>
            <p className="mt-6 max-w-[30rem] text-[1.0625rem] text-fg-muted">Crea el espacio de tu empresa y deja tu primer tablero listo en minutos.</p>
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
          <span>Kuador S.A.S. · Desde la Mitad del Mundo.</span>
          <a href="https://kuador.com" className="inline-flex items-center gap-1 hover:text-fg-strong">
            kuador.com <ArrowUpRight className="size-3.5" aria-hidden />
          </a>
        </div>
      </footer>
    </div>
  );
}

function FlowLine({ steps }: { steps: string[] }) {
  return (
    <p className="flex flex-wrap items-center gap-x-2 gap-y-1 font-mono text-[12px] text-fg-muted">
      {steps.map((step, i) => (
        <span key={step} className="flex items-center gap-2">
          {step}
          {i < steps.length - 1 && <span className="text-fg-faint">→</span>}
        </span>
      ))}
    </p>
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
