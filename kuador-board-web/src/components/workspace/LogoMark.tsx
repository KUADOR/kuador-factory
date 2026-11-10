"use client";

import { clsx } from "clsx";
import { useEffect, useState } from "react";

import { useThemeMode } from "@/theme/useThemeMode";

type Ink = "dark" | "light" | "unknown";

/** Luminancia media de los píxeles visibles del logo: decide si es «tinta oscura» o «tinta clara». */
function measureInk(src: string): Promise<Ink> {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      const w = Math.min(64, img.naturalWidth);
      const h = Math.max(1, Math.round((img.naturalHeight / img.naturalWidth) * w));
      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;
      const ctx = canvas.getContext("2d", { willReadFrequently: true });
      if (!ctx) return resolve("unknown");
      ctx.drawImage(img, 0, 0, w, h);
      const data = ctx.getImageData(0, 0, w, h).data;
      let sum = 0;
      let count = 0;
      let opaque = 0;
      for (let i = 0; i < data.length; i += 4) {
        const alpha = data[i + 3] / 255;
        if (alpha < 0.5) continue;
        opaque++;
        sum += (0.2126 * data[i] + 0.7152 * data[i + 1] + 0.0722 * data[i + 2]) / 255;
        count++;
      }
      // Un logo sin transparencia trae su propio fondo: se ve igual en cualquier tema.
      if (opaque > (data.length / 4) * 0.97) return resolve("unknown");
      resolve(count === 0 ? "unknown" : sum / count < 0.45 ? "dark" : "light");
    };
    img.onerror = () => resolve("unknown");
    img.src = src;
  });
}

/**
 * Logo de la empresa. Si su tinta se pierde en el tema actual (letras negras sobre la noche, o
 * blancas sobre papel), se apoya en una placa del color contrario para que siempre se lea.
 */
export function LogoMark({ src, name, className, theme }: { src: string; name: string; className?: string; theme?: "dark" | "light" }) {
  const [ink, setInk] = useState<Ink>("unknown");
  const pageMode = useThemeMode();
  const mode = theme ?? pageMode;

  useEffect(() => {
    let active = true;
    void measureInk(src).then((value) => active && setInk(value));
    return () => {
      active = false;
    };
  }, [src]);

  const plate = ink === "dark" && mode === "dark" ? "bg-white" : ink === "light" && mode === "light" ? "bg-[#0b1017]" : null;
  return (
    <span className={clsx("inline-flex max-w-full items-center", plate && `${plate} rounded-md px-2 py-1`, className)}>
      {/* eslint-disable-next-line @next/next/no-img-element -- logo del espacio en data URL */}
      <img src={src} alt={name} className="block max-h-full max-w-full object-contain" />
    </span>
  );
}
