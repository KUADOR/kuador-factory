import type { Metadata } from "next";
import Link from "next/link";
import { clsx } from "clsx";

import { CompanyDashboard } from "@/components/insights/CompanyDashboard";
import { MyWork } from "@/components/insights/MyWork";
import { PageHeader } from "@/components/shell/PageHeader";
import { FirstSteps } from "@/components/projects/FirstSteps";
import { canAdminister, canEdit } from "@/lib/access";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { PulseView } from "./pulse/PulseView";
import type { InsightsCompany, InsightsMine, RunnerMachine } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Inicio" };

const PERIODS = [7, 30, 90];

type Props = { searchParams: Promise<{ view?: string; days?: string }> };

/**
 * Inicio: quien administra ve cómo va la empresa (y puede pasar a su propio trabajo); el resto del equipo, su trabajo.
 * Todos tienen además el pulso del día: las últimas 24 horas.
 */
export default async function HomePage({ searchParams }: Props) {
  const { view, days: rawDays } = await searchParams;
  let me;
  try {
    me = await boardApi.me();
  } catch (e) {
    return failPage(e);
  }
  const admin = canAdminister(me);
  // Empresa recién creada: en vez de métricas vacías, por dónde empezar.
  const pulse = view === "pulse";
  if (canEdit(me) && view !== "me" && !pulse) {
    const boards = await boardApi.projects().catch(() => null);
    if (boards != null && boards.length === 0) {
      return (
        <>
          <PageHeader title={`Hola, ${me.name.split(" ")[0]}`} description="Todavía no hay tableros en tu empresa." />
          <div className="px-4 py-5 sm:px-6 sm:py-6">
            <FirstSteps canDesign={admin} />
          </div>
        </>
      );
    }
  }
  const showCompany = admin && view !== "me" && !pulse;
  const days = PERIODS.includes(Number(rawDays)) ? Number(rawDays) : 30;

  // Los datos se piden dentro del try; la vista se arma afuera (un error de datos muestra la página de error).
  let company: InsightsCompany | null = null;
  let mine: { data: InsightsMine; decisions: number; machines: RunnerMachine[] } | null = null;
  try {
    if (pulse) {
      // El pulso pide lo suyo.
    } else if (showCompany) {
      company = await boardApi.insightsCompany(days);
    } else {
      const [data, decisions, machines] = await Promise.all([
        boardApi.insightsMine(),
        boardApi.decisions().catch(() => []),
        boardApi.runnerMachines().catch(() => []),
      ]);
      mine = { data, decisions: decisions.length, machines: machines.filter((m) => m.memberId === me.id) };
    }
  } catch (e) {
    return failPage(e);
  }
  const body = pulse ? (
    <PulseView />
  ) : company ? (
    <CompanyDashboard data={company} canEditSettings={admin} />
  ) : mine ? (
    <MyWork data={mine.data} decisions={mine.decisions} machines={mine.machines} />
  ) : null;

  const tab = (active: boolean) =>
    clsx("rounded-md px-2.5 py-1.5 text-[13px] whitespace-nowrap", active ? "bg-selected font-medium text-fg-strong shadow-[0_0_0_1px_var(--k-line)]" : "text-fg-muted hover:bg-hover hover:text-fg-strong");

  return (
    <>
      <PageHeader
        title={pulse ? "Pulso del día" : showCompany ? "Cómo va la empresa" : `Hola, ${me.name.split(" ")[0]}`}
        description={pulse ? "Las últimas 24 horas de tus tableros." : showCompany ? "Entregas, tiempos y ahorro en todos tus tableros." : undefined}
      >
        {
          <nav aria-label="Vista" className="-mx-1 flex flex-wrap items-center gap-1 px-1 pb-3">
            {admin && (
              <Link href={`/?days=${days}`} className={tab(showCompany)} aria-current={showCompany ? "page" : undefined}>
                Empresa
              </Link>
            )}
            <Link href="/?view=me" className={tab(!showCompany && !pulse)} aria-current={!showCompany && !pulse ? "page" : undefined}>
              Mi trabajo
            </Link>
            <Link href="/?view=pulse" className={tab(pulse)} aria-current={pulse ? "page" : undefined}>
              Pulso del día
            </Link>
            {showCompany && (
              <span className="ml-auto flex items-center gap-1" aria-label="Período">
                {PERIODS.map((d) => (
                  <Link key={d} href={`/?days=${d}`} className={tab(d === days)} aria-current={d === days ? "true" : undefined}>
                    {d} días
                  </Link>
                ))}
              </span>
            )}
          </nav>
        }
      </PageHeader>
      <div className="px-4 py-5 sm:px-6 sm:py-6">{body}</div>
    </>
  );
}
