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
  mobileAside,
  width = "full",
  children,
}: {
  crumbs?: Crumb[];
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  /** Solo en el celular, al lado del título (p. ej. el selector de sección): ahorra una fila. */
  mobileAside?: React.ReactNode;
  /** Ancho del contenido: listas y tablas usan todo el ancho; «narrow» para páginas de un solo formulario. */
  width?: "narrow" | "full";
  /** Fila inferior (pestañas, filtros) pegada a la cabecera. */
  children?: React.ReactNode;
}) {
  return (
    <header className="border-b border-line bg-page pt-3 md:pt-4">
      {/* Mismo cuadro que PageBody (relleno dentro del ancho máximo): título y acciones se alinean con el contenido. */}
      <div className={width === "full" ? "px-4 sm:px-6" : "max-w-3xl px-4 sm:px-6"}>
      {crumbs && crumbs.length > 0 && (
        <nav aria-label="Migas" className="mb-1.5 hidden items-center gap-1 text-xs text-fg-faint md:flex">
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
      <div className="flex flex-wrap items-start justify-between gap-3 pb-3 md:pb-4">
        <div className="min-w-0 flex-1 md:flex-none">
          <h1 className="text-lg font-semibold tracking-tight text-balance text-fg-strong md:text-xl">{title}</h1>
          {description && <p className="mt-1 line-clamp-1 max-w-3xl text-[13px] leading-relaxed text-fg-muted md:line-clamp-none">{description}</p>}
        </div>
        {mobileAside && <div className="max-w-[55%] shrink-0 md:hidden">{mobileAside}</div>}
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
  return <div className={`px-4 py-5 sm:px-6 sm:py-6 ${max}`}>{children}</div>;
}
