import { clsx } from "clsx";
import { Columns3, List } from "lucide-react";
import Link from "next/link";

/** Tablero (el trabajo en curso) o lista (todas las tarjetas, con filtros, orden y páginas). */
export function ViewSwitch({ projectKey, view, className }: { projectKey: string; view: "board" | "list"; className?: string }) {
  const item = (target: "board" | "list", label: string, Icon: typeof List) => (
    <Link
      href={target === "board" ? `/projects/${projectKey}` : `/projects/${projectKey}?view=list`}
      aria-current={view === target ? "page" : undefined}
      className={clsx(
        "flex items-center gap-1.5 rounded px-2 py-1 text-xs",
        view === target ? "bg-raised text-fg-strong shadow-sm" : "text-fg-muted hover:text-fg-strong",
      )}
    >
      <Icon className="size-3.5" aria-hidden />
      {label}
    </Link>
  );
  return (
    <nav aria-label="Vista" className={clsx("flex items-center gap-0.5 rounded-md bg-sunken p-0.5", className)}>
      {item("board", "Tablero", Columns3)}
      {item("list", "Lista", List)}
    </nav>
  );
}
