"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { LogOut, Plus, Settings, ShieldCheck, Users } from "lucide-react";
import { clsx } from "clsx";

import { logoutAction } from "@/app/actions";
import { Avatar } from "@/components/ui/Avatar";
import { canAdminister, canEdit } from "@/lib/access";
import { LogoMark } from "@/components/workspace/LogoMark";
import type { Me, Project, Workspace } from "@/lib/types";
import type { ThemeMode } from "@/theme/theme";

import { SETTINGS_PATHS } from "./settings-paths";
import { ThemeToggle } from "./ThemeToggle";

function NavLink({ href, active, children }: { href: string; active: boolean; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      className={clsx(
        "flex h-8 items-center gap-2.5 rounded-md px-2.5 text-[13px]",
        active ? "bg-selected font-medium text-fg-strong shadow-[0_0_0_1px_var(--k-line)]" : "text-fg-muted hover:bg-hover/60 hover:text-fg-strong",
      )}
    >
      {children}
    </Link>
  );
}

/** Navegación principal: los proyectos arriba; abajo, configuración del espacio y plataforma; al pie, la persona y el tema. */
export function SidebarNav({ me, workspace, projects, theme }: { me: Me; workspace: Workspace | null; projects: Project[]; theme: ThemeMode }) {
  const path = usePathname();
  const inProject = (key: string) => path === `/projects/${key}` || path.startsWith(`/projects/${key}/`);
  const editor = canEdit(me);
  const admin = canAdminister(me);
  return (
    <div className="flex h-full flex-col">
      {workspace?.logo ? (
        // Con logo, el logo es la identidad: ocupa la cabecera y el nombre queda como texto alternativo.
        <Link href="/" title={workspace.name} className="flex h-16 shrink-0 items-center border-b border-line px-4">
          <LogoMark src={workspace.logo} name={workspace.name} className="h-10 max-w-[12rem]" />
        </Link>
      ) : (
        <Link href="/" className="flex h-16 shrink-0 items-center gap-2.5 border-b border-line px-4">
          <span aria-hidden className="size-2.5 shrink-0 rounded-full bg-sol" />
          <span className="min-w-0">
            <span className="block truncate text-[13px] font-semibold tracking-tight text-fg-strong">{workspace?.name ?? "Tablero"}</span>
          </span>
        </Link>
      )}

      <nav aria-label="Principal" className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto px-2 py-4">
        <div className="flex flex-col gap-0.5">
          <div className="flex h-7 items-center justify-between pr-1 pl-2.5">
            <Link href="/" className={clsx("text-[11px] font-medium", path === "/" ? "text-fg-strong" : "text-fg-faint hover:text-fg-strong")}>
              Proyectos
            </Link>
            {editor && (
              <Link href="/?nuevo=1" aria-label="Nuevo proyecto" title="Nuevo proyecto" className="grid size-6 place-items-center rounded text-fg-faint hover:bg-hover hover:text-fg-strong">
                <Plus className="size-3.5" aria-hidden />
              </Link>
            )}
          </div>
          {projects.map((p) => (
            <NavLink key={p.key} href={`/projects/${p.key}`} active={inProject(p.key)}>
              <span className="w-10 shrink-0 font-mono text-[10px] text-fg-faint">{p.key}</span>
              <span className="truncate">{p.name}</span>
            </NavLink>
          ))}
          {projects.length === 0 && <p className="px-2.5 py-1 text-xs text-fg-faint">Aún no hay proyectos.</p>}
        </div>

        {/* Lo de todos los días son los proyectos; la configuración del espacio vive en un solo lugar, con su submenú. */}
        <div className="mt-auto flex flex-col gap-0.5 border-t border-line pt-3">
          <NavLink href={admin ? "/workspace" : "/team"} active={SETTINGS_PATHS.some((p) => path === p || path.startsWith(p + "/"))}>
            {admin ? <Settings className="size-4 shrink-0" aria-hidden /> : <Users className="size-4 shrink-0" aria-hidden />}
            {admin ? "Configuración" : "Equipo"}
          </NavLink>
          {me.platformAdmin && (
            <NavLink href="/platform" active={path.startsWith("/platform")}>
              <ShieldCheck className="size-4 shrink-0" aria-hidden /> Plataforma
            </NavLink>
          )}
        </div>
      </nav>

      <div className="flex shrink-0 items-center gap-1 border-t border-line p-2">
        <Link href="/account" className="flex min-w-0 flex-1 items-center gap-2 rounded-md px-2 py-1.5 hover:bg-hover">
          <Avatar member={me} size="sm" />
          <span className="min-w-0">
            <span className="block truncate text-[13px] text-fg">{me.name}</span>
            {!editor && <span className="block text-[11px] text-fg-faint">Solo lectura</span>}
          </span>
        </Link>
        <ThemeToggle initial={theme} />
        <form action={logoutAction}>
          <button type="submit" aria-label="Cerrar sesión" title="Cerrar sesión" className="grid size-8 place-items-center rounded-md text-fg-muted hover:bg-hover hover:text-fg-strong">
            <LogOut className="size-4" aria-hidden />
          </button>
        </form>
      </div>
    </div>
  );
}
