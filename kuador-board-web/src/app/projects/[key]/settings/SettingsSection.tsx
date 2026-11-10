/** Encabezado de una sección de configuración: título, para qué sirve y su contenido. */
export function SettingsSection({ title, description, children }: { title: string; description?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="flex max-w-4xl flex-col gap-5">
      <header>
        <h2 className="text-base font-semibold text-fg-strong">{title}</h2>
        {description && <p className="mt-1 max-w-3xl text-[13px] leading-relaxed text-fg-muted">{description}</p>}
      </header>
      {children}
    </section>
  );
}
