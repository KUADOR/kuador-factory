"use client";

import QRCode from "qrcode";
import { useEffect, useState } from "react";

/**
 * Código QR de un enlace, para abrirlo con la cámara del teléfono. El fondo es blanco siempre: muchas cámaras no leen
 * un QR invertido (modo oscuro).
 */
export function QrCode({ value, label, className = "size-44" }: { value: string; label: string; className?: string }) {
  const [svg, setSvg] = useState<{ value: string; markup: string } | null>(null);

  useEffect(() => {
    let alive = true;
    void QRCode.toString(value, { type: "svg", margin: 1, errorCorrectionLevel: "M", color: { dark: "#000000", light: "#ffffff" } }).then((markup) => {
      if (alive) setSvg({ value, markup });
    });
    return () => {
      alive = false;
    };
  }, [value]);

  return (
    <div
      role="img"
      aria-label={label}
      className={`${className} shrink-0 rounded-md bg-white p-2 [&>svg]:size-full`}
      dangerouslySetInnerHTML={svg?.value === value ? { __html: svg.markup } : undefined}
    />
  );
}
