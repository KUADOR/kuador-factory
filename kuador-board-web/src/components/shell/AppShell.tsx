"use client";

import { Drawer } from "@primereact/ui/drawer";
import { Menu } from "lucide-react";
import { usePathname } from "next/navigation";
import { useState } from "react";

import type { Me, Project, Workspace } from "@/lib/types";
import type { ThemeMode } from "@/theme/theme";

import { LogoMark } from "@/components/workspace/LogoMark";

import { SidebarNav } from "./SidebarNav";

/**
 * Armazón de la app: barra lateral fija en escritorio; en pantallas angostas la misma navegación
 * se abre como panel desde la izquierda.
 */
export function AppShell(props: { me: Me; workspace: Workspace | null; projects: Project[]; theme: ThemeMode; children: React.ReactNode }) {
  const { children, ...nav } = props;
  // El panel recuerda en qué ruta se abrió: al navegar a otra, queda cerrado sin efectos extra.
  const path = usePathname();
  const [openAt, setOpenAt] = useState<string | null>(null);
  const open = openAt === path;
  const setOpen = (value: boolean) => setOpenAt(value ? path : null);

  return (
    <div className="flex h-dvh">
      <aside className="hidden w-60 shrink-0 border-r border-line bg-rail lg:block">
        <SidebarNav {...nav} />
      </aside>

      <Drawer.Root open={open} onOpenChange={(e: { value?: boolean }) => setOpen(!!e.value)} position="left">
        <Drawer.Portal>
          <Drawer.Backdrop />
          <Drawer.Popup className="w-64 bg-rail p-0">
            <SidebarNav {...nav} />
          </Drawer.Popup>
        </Drawer.Portal>
      </Drawer.Root>

      <div className="flex min-w-0 flex-1 flex-col">
        <div className="flex h-12 shrink-0 items-center gap-2 border-b border-line px-3 lg:hidden">
          <button
            type="button"
            onClick={() => setOpen(true)}
            aria-label="Abrir navegación"
            className="grid size-8 place-items-center rounded-md text-fg-muted hover:bg-hover hover:text-fg-strong"
          >
            <Menu className="size-4" aria-hidden />
          </button>
          {nav.workspace?.logo ? (
            <LogoMark src={nav.workspace.logo} name={nav.workspace.name} className="h-7 max-w-[10rem]" />
          ) : (
            <>
              <span aria-hidden className="size-2 rounded-full bg-sol" />
              <span className="text-[13px] font-semibold text-fg-strong">{nav.workspace?.name ?? "Kuador"}</span>
            </>
          )}
        </div>
        <main className="min-h-0 flex-1 overflow-y-auto">{children}</main>
      </div>
    </div>
  );
}
