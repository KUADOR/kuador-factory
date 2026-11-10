import Link from "next/link";
import { ChevronRight } from "lucide-react";

export interface Crumb {
  label: string;
  href?: string;
}

/** Cabecera de página: migas, título, descripción opcional y acciones a la derecha. */
export function PageHeader({
  crumbs,
  title,
  description,
  actions,
  width = "full",
  children,
}: {
  crumbs?: Crumb[];
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  /** Ancho del contenido: listas y tablas usan todo el ancho; «narrow» para páginas de un solo formulario. */
  width?: "narrow" | "full";
  /** Fila inferior (pestañas, filtros) pegada a la cabecera. */
  children?: React.ReactNode;
}) {
  return (
    <header className="border-b border-line bg-page pt-4">
      {/* Mismo cuadro que PageBody (relleno dentro del ancho máximo): título y acciones se alinean con el contenido. */}
      <div className={width === "full" ? "px-6" : "max-w-3xl px-6"}>
      {crumbs && crumbs.length > 0 && (
        <nav aria-label="Migas" className="mb-1.5 flex items-center gap-1 text-xs text-fg-faint">
          {crumbs.map((c, i) => (
            <span key={`${c.label}-${i}`} className="flex items-center gap-1">
              {i > 0 && <ChevronRight className="size-3" aria-hidden />}
              {c.href ? (
                <Link href={c.href} className="hover:text-fg-strong">
                  {c.label}
                </Link>
              ) : (
                <span>{c.label}</span>
              )}
            </span>
          ))}
        </nav>
      )}
      <div className="flex flex-wrap items-start justify-between gap-3 pb-4">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold tracking-tight text-balance text-fg-strong">{title}</h1>
          {description && <p className="mt-1 max-w-3xl text-[13px] leading-relaxed text-fg-muted">{description}</p>}
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {children}
      </div>
    </header>
  );
}

/** Contenido bajo la cabecera, con el ancho que pida la página. */
export function PageBody({ width = "full", children }: { width?: "narrow" | "full"; children: React.ReactNode }) {
  const max = { narrow: "max-w-3xl", full: "" }[width];
  return <div className={`px-6 py-6 ${max}`}>{children}</div>;
}
