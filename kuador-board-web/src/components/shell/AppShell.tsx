"use client";

import { Drawer } from "@primereact/ui/drawer";
import { House, Inbox, LayoutGrid, Menu, Plus } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Suspense, useState } from "react";
import { clsx } from "clsx";

import { canEdit } from "@/lib/access";
import type { Me, Project, Workspace, WorkspacePlan } from "@/lib/types";
import type { ThemeMode } from "@/theme/theme";

import { KuadorLogo } from "@/components/brand/KuadorLogo";
import { LogoMark } from "@/components/workspace/LogoMark";

import { AccessProvider } from "./AccessContext";
import { PlanBanner } from "./PlanBanner";
import { RouteProgress } from "./RouteProgress";
import { SidebarNav } from "./SidebarNav";
import { useDecisionsCount } from "./useDecisionsCount";

/**
 * Armazón de la app. En escritorio, barra lateral fija. En el celular (y en la app instalada), barra superior con el
 * espacio y una barra inferior con íconos, como cualquier app: Inicio, Proyectos, Capturar, Decisiones y Menú (la misma
 * navegación de escritorio, en un panel). Respeta los bordes del teléfono: la hora y la Dynamic Island arriba, la barra
 * de inicio abajo y las esquinas en horizontal.
 */
export function AppShell(props: {
  me: Me;
  workspace: Workspace | null;
  projects: Project[];
  plan: WorkspacePlan | null;
  decisions: number;
  theme: ThemeMode;
  children: React.ReactNode;
}) {
  const { children, plan, ...rest } = props;
  const nav = { ...rest, decisions: useDecisionsCount(rest.decisions) };
  // El panel recuerda en qué ruta se abrió: al navegar a otra, queda cerrado sin efectos extra.
  const path = usePathname();
  const [openAt, setOpenAt] = useState<string | null>(null);
  const open = openAt === path;
  const setOpen = (value: boolean) => setOpenAt(value ? path : null);

  return (
    <AccessProvider me={props.me}>
      <div className="flex h-dvh pr-[env(safe-area-inset-right)] pl-[env(safe-area-inset-left)]">
        <aside className="hidden w-60 shrink-0 border-r border-line bg-rail lg:block">
          <SidebarNav {...nav} />
        </aside>

        <Drawer.Root open={open} onOpenChange={(e: { value?: boolean }) => setOpen(!!e.value)} position="left">
          <Drawer.Portal>
            <Drawer.Backdrop />
            <Drawer.Popup className="w-72 bg-rail p-0 pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]">
              <SidebarNav {...nav} />
            </Drawer.Popup>
          </Drawer.Portal>
        </Drawer.Root>

        <div className="flex min-w-0 flex-1 flex-col">
          {/* Celular: el espacio debajo de la hora y la Dynamic Island. */}
          <header className="shrink-0 border-b border-line bg-rail pt-[env(safe-area-inset-top)] lg:hidden">
            <div className="flex h-12 items-center gap-2 px-4">
              {nav.workspace?.logo ? (
                <LogoMark src={nav.workspace.logo} name={nav.workspace.name} className="h-7 max-w-[10rem]" />
              ) : (
                <>
                  <KuadorLogo className="size-7" title="" />
                  <span className="truncate text-[15px] font-semibold text-fg-strong">{nav.workspace?.name ?? "Tablero"}</span>
                </>
              )}
            </div>
          </header>
          {plan && <PlanBanner plan={plan} />}
          <div className="relative flex min-h-0 flex-1 flex-col">
            <Suspense fallback={null}>
              <RouteProgress />
            </Suspense>
            <main className="min-h-0 flex-1 overflow-y-auto">{children}</main>
          </div>
          <TabBar path={path} decisions={nav.decisions} canCapture={canEdit(nav.me)} onMenu={() => setOpen(true)} />
        </div>
      </div>
    </AccessProvider>
  );
}

/** Barra inferior del celular: lo de todos los días a un toque, con el pulgar. */
function TabBar({ path, decisions, canCapture, onMenu }: { path: string; decisions: number; canCapture: boolean; onMenu: () => void }) {
  const inProjects = path.startsWith("/projects") || path === "/design";
  const item = (active: boolean) =>
    clsx("flex flex-1 flex-col items-center justify-center gap-0.5 text-[10px] font-medium", active ? "text-sol-text" : "text-fg-muted");
  return (
    <nav aria-label="Navegación" className="shrink-0 border-t border-line bg-rail pb-[env(safe-area-inset-bottom)] lg:hidden">
      <div className="flex h-16 items-stretch">
        <Link href="/" className={item(path === "/")} aria-current={path === "/" ? "page" : undefined}>
          <House className="size-6" aria-hidden />
          Inicio
        </Link>
        <Link href="/projects" className={item(inProjects)} aria-current={inProjects ? "page" : undefined}>
          <LayoutGrid className="size-6" aria-hidden />
          Tableros
        </Link>
        {canCapture && (
          <Link href="/capture" className={clsx(item(path === "/capture"), "gap-0")} aria-current={path === "/capture" ? "page" : undefined}>
            <span className="grid size-9 place-items-center rounded-full bg-sol text-on-sol shadow-md">
              <Plus className="size-5" aria-hidden />
            </span>
            Capturar
          </Link>
        )}
        <Link href="/decisions" className={item(path === "/decisions")} aria-current={path === "/decisions" ? "page" : undefined}>
          <span className="relative">
            <Inbox className="size-6" aria-hidden />
            {decisions > 0 && (
              <span className="tabular absolute -top-1.5 -right-2.5 grid h-4 min-w-4 place-items-center rounded-full bg-sol px-1 text-[10px] leading-none font-semibold text-on-sol">
                {decisions > 99 ? "99+" : decisions}
              </span>
            )}
          </span>
          Mis decisiones
        </Link>
        <button type="button" onClick={onMenu} className={item(false)}>
          <Menu className="size-6" aria-hidden />
          Menú
        </button>
      </div>
    </nav>
  );
}
