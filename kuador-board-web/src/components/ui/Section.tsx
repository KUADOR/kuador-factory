import { clsx } from "clsx";

/** Bloque con título dentro de una página (configuración, listas). Un solo nivel: nunca uno dentro de otro. */
export function Section({
  title,
  description,
  actions,
  children,
  className,
  flush,
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  /** Sin relleno interior: para tablas y listas que llegan al borde. */
  flush?: boolean;
}) {
  return (
    <section className={clsx("rounded-lg border border-line bg-surface", className)}>
      <header className="flex flex-wrap items-start justify-between gap-3 border-b border-line px-5 py-3.5">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-fg-strong">{title}</h2>
          {description && <p className="mt-0.5 max-w-3xl text-[13px] leading-relaxed text-fg-muted">{description}</p>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </header>
      <div className={flush ? undefined : "px-5 py-4"}>{children}</div>
    </section>
  );
}

/** Estado vacío que explica qué va aquí y cómo empezar. */
export function EmptyState({ title, children, action }: { title: string; children?: React.ReactNode; action?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-start gap-2 rounded-lg border border-dashed border-line-strong px-5 py-6">
      <p className="text-sm font-medium text-fg-strong">{title}</p>
      {children && <div className="max-w-xl text-[13px] leading-relaxed text-fg-muted">{children}</div>}
      {action && <div className="mt-1">{action}</div>}
    </div>
  );
}

const TONE = {
  neutral: "bg-hover text-fg-muted",
  sol: "bg-sol/15 text-sol-text",
  ok: "bg-ok/12 text-ok",
  danger: "bg-danger/12 text-danger",
  info: "bg-info/12 text-info",
  warn: "bg-warn/12 text-warn",
  epic: "bg-epic/12 text-epic",
} as const;

/** Etiqueta de estado corta (obligatorio, de fábrica, sincronizado…). */
export function Badge({ tone = "neutral", children, className }: { tone?: keyof typeof TONE; children: React.ReactNode; className?: string }) {
  return <span className={clsx("inline-flex items-center gap-1 rounded px-1.5 py-px text-[11px] font-medium whitespace-nowrap", TONE[tone], className)}>{children}</span>;
}
