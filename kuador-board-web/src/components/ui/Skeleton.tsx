import { clsx } from "clsx";

/** Bloque gris que late mientras llega la información (sin animación si la persona pidió menos movimiento). */
export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden className={clsx("rounded-md bg-hover motion-safe:animate-pulse", className)} />;
}

/** Aviso para lectores de pantalla mientras se carga. */
function Loading({ children }: { children: React.ReactNode }) {
  return (
    <div role="status" aria-live="polite" className="min-w-0">
      <span className="sr-only">Cargando…</span>
      {children}
    </div>
  );
}

/** Página genérica: cabecera y unas tarjetas con filas. */
export function PageSkeleton({ header = true }: { header?: boolean }) {
  return (
    <Loading>
      {header && (
        <div className="border-b border-line px-6 pt-5 pb-4">
          <Skeleton className="h-6 w-48" />
          <Skeleton className="mt-2.5 h-3.5 w-80 max-w-full" />
        </div>
      )}
      <div className="flex flex-col gap-6 px-6 py-6">
        {[0, 1].map((i) => (
          <div key={i} className="rounded-lg border border-line p-5">
            <Skeleton className="h-4 w-40" />
            <Skeleton className="mt-2 h-3 w-96 max-w-full" />
            <div className="mt-5 flex flex-col gap-3">
              <Skeleton className="h-9 w-full" />
              <Skeleton className="h-9 w-full" />
              <Skeleton className="h-9 w-2/3" />
            </div>
          </div>
        ))}
      </div>
    </Loading>
  );
}

/** Tablero: columnas con tarjetas. */
export function BoardSkeleton() {
  return (
    <Loading>
      <div className="flex gap-4 overflow-hidden px-6 py-6">
        {[4, 3, 2, 1].map((cards, i) => (
          <div key={i} className="flex w-72 shrink-0 flex-col gap-3">
            <Skeleton className="h-4 w-28" />
            {Array.from({ length: cards }, (_, j) => (
              <div key={j} className="rounded-lg border border-line p-3">
                <Skeleton className="h-3.5 w-full" />
                <Skeleton className="mt-2 h-3.5 w-2/3" />
                <Skeleton className="mt-4 h-3 w-16" />
              </div>
            ))}
          </div>
        ))}
      </div>
    </Loading>
  );
}

/** Una sección de formulario (configuración): título, descripción y campos. */
export function FormSkeleton() {
  return (
    <Loading>
      <div className="flex flex-col gap-4">
        <Skeleton className="h-5 w-52" />
        <Skeleton className="h-3.5 w-full max-w-xl" />
        <div className="mt-2 flex flex-col gap-4 rounded-lg border border-line p-5">
          {[0, 1, 2].map((i) => (
            <div key={i}>
              <Skeleton className="h-3 w-24" />
              <Skeleton className="mt-2 h-9 w-full" />
            </div>
          ))}
        </div>
      </div>
    </Loading>
  );
}
