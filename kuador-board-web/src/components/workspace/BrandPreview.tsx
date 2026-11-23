"use client";

import { brandFrom } from "@/theme/brand";
import { useThemeMode } from "@/theme/useThemeMode";

import type { BrandDraft } from "./BrandFields";
import { LogoMark } from "./LogoMark";

/**
 * Miniatura del tablero con la marca: barra lateral con el logo, dos columnas y el botón primario.
 * Usa los tokens del tema actual, así se juzga el color sobre el fondo que verá el equipo.
 */
export function BrandPreview({ value }: { value: BrandDraft }) {
  const mode = useThemeMode();
  const brand = brandFrom(/^#[0-9a-f]{6}$/i.test(value.brandColor) ? value.brandColor : undefined, value.brandOnColor);
  const brandText = mode === "dark" ? brand.textDark : brand.textLight;
  return (
    <div aria-label="Vista previa del tablero" className="overflow-hidden rounded-lg border border-line-strong bg-page text-[11px] text-fg shadow-float">
      <div className="flex h-56">
        <div className="flex w-32 shrink-0 flex-col gap-2 border-r border-line bg-rail p-2.5">
          <div className="flex h-7 items-center gap-1.5">
            {value.logo ? (
              <LogoMark src={value.logo} name={value.name || "Tu empresa"} className="h-6 max-w-full" />
            ) : (
              <>
                <span className="size-2 shrink-0 rounded-full" style={{ backgroundColor: brand.color }} />
                <span className="truncate font-semibold text-fg-strong">{value.name || "Tu empresa"}</span>
              </>
            )}
          </div>
          <span className="mt-2 rounded bg-selected px-1.5 py-1 font-medium text-fg-strong shadow-[0_0_0_1px_var(--k-line)]">Proyecto</span>
          <span className="px-1.5 text-fg-muted">Equipo</span>
          <span className="px-1.5 text-fg-muted">Catálogo</span>
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-2 p-3">
          <div className="flex items-center justify-between">
            <span className="font-semibold text-fg-strong">Tablero</span>
            <span className="rounded px-2 py-1 font-medium" style={{ backgroundColor: brand.color, color: brand.on }}>
              + Crear tarjeta
            </span>
          </div>
          <div className="mt-1 h-0.5 w-12 rounded" style={{ backgroundColor: brand.color }} />
          <div className="flex gap-2">
            {["Por hacer", "En curso"].map((c, i) => (
              <div key={c} className="flex w-1/2 flex-col gap-1.5 rounded bg-sunken p-1.5">
                <span className="text-[10px] font-semibold text-fg-strong">{c}</span>
                <div className="rounded border bg-raised p-1.5" style={{ borderColor: i === 0 ? brand.color : "var(--k-line-strong)" }}>
                  <span className="block text-fg-strong">Agenda de citas</span>
                  <span className="mt-1 block font-mono text-[9px] text-fg-muted">MED-1</span>
                </div>
                <div className="rounded border border-line-strong bg-raised p-1.5">
                  <span className="block text-fg-strong">Recordatorio</span>
                  {i === 1 && (
                    <span className="mt-1 block text-[9px]" style={{ color: brandText }}>
                      Agente trabajando…
                    </span>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
