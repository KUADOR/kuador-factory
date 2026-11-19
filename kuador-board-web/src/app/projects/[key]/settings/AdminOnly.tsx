"use client";

import { useAccess } from "@/components/shell/AccessContext";

/**
 * Columnas, etapas y transiciones deciden quién aprueba cada puerta: solo las cambia quien administra el espacio.
 * El resto del equipo las ve sin poder tocarlas (la API también lo rechaza).
 */
export function AdminOnly({ children }: { children: React.ReactNode }) {
  const { canAdminister } = useAccess();
  if (canAdminister) return <>{children}</>;
  return (
    <div className="flex flex-col gap-3">
      <p role="status" className="text-[13px] text-fg-muted">
        Solo quien administra el espacio cambia esta sección.
      </p>
      <fieldset disabled aria-disabled className="pointer-events-none min-w-0 opacity-70 select-none">
        {children}
      </fieldset>
    </div>
  );
}
