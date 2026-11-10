"use client";

import { PrimeReactProvider, PrimeReactStyleSheet } from "@primereact/core";
import { useServerInsertedHTML } from "next/navigation";
import { useMemo, useState } from "react";

import { brandFrom } from "./brand";
import { brandedPreset } from "./kuador-preset";

/**
 * PrimeReact con el tema Kuador. En el servidor recoge los estilos que generan los componentes y
 * los inserta en el HTML, para que la primera pintura ya llegue con tema (sin destello).
 * La clave de licencia PrimeUI la pasa el layout desde el entorno (PRIMEUI_LICENSE).
 */
export function PrimeProvider({ license, brandColor, brandOnColor, children }: { license?: string; brandColor?: string; brandOnColor?: string; children: React.ReactNode }) {
  const [sheet] = useState(() => new PrimeReactStyleSheet());
  const preset = useMemo(() => brandedPreset(brandFrom(brandColor, brandOnColor)), [brandColor, brandOnColor]);
  useServerInsertedHTML(() => {
    const styles = sheet.getAllElements();
    sheet.clear();
    return <>{styles}</>;
  });
  return (
    <PrimeReactProvider
      stylesheet={sheet}
      license={license}
      locale="es"
      theme={{ preset, options: { darkModeSelector: ".app-dark", cssLayer: { name: "primeui", order: "theme, base, primeui, components, utilities" } } }}
    >
      {children}
    </PrimeReactProvider>
  );
}
