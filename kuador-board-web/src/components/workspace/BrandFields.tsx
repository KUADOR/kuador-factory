"use client";

import { ImageUp, X } from "lucide-react";
import { clsx } from "clsx";
import { useRef, useState } from "react";

import { Button } from "@/components/ui/Button";
import { Field, TextArea, TextInput } from "@/components/ui/Field";
import { BRAND_PRESETS, autoOn, brandFrom, contrast } from "@/theme/brand";

import { LogoMark } from "./LogoMark";

export interface BrandDraft {
  name: string;
  description: string;
  brandColor: string;
  /** Texto de los botones sobre el color de marca; nulo = automático. */
  brandOnColor: string | null;
  logo: string | null;
}

/** Máximo que acepta la API (~150 KB); el logo se reduce a 256 px antes de subirlo. */
const MAX_LOGO = 200_000;

/** Reduce la imagen a 256 px de lado mayor y la convierte a WebP (o PNG si el navegador no puede). */
async function shrink(file: File): Promise<string> {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 256 / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext("2d")!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  const webp = canvas.toDataURL("image/webp", 0.9);
  return webp.startsWith("data:image/webp") ? webp : canvas.toDataURL("image/png");
}

/** Nombre, descripción, color base y logo de la empresa. Lo usan el onboarding y la configuración del espacio. */
export function BrandFields({ value, onChange, nameLabel = "Nombre de la empresa" }: { value: BrandDraft; onChange: (v: BrandDraft) => void; nameLabel?: string }) {
  const input = useRef<HTMLInputElement>(null);
  const [logoError, setLogoError] = useState<string | null>(null);
  const set = (patch: Partial<BrandDraft>) => onChange({ ...value, ...patch });
  const validHex = /^#[0-9a-f]{6}$/i.test(value.brandColor);
  const brand = brandFrom(validHex ? value.brandColor : undefined, value.brandOnColor);
  const ratio = contrast(brand.color, brand.on);
  const textMode = value.brandOnColor == null ? "auto" : value.brandOnColor === "#0b1017" ? "black" : value.brandOnColor === "#ffffff" ? "white" : "custom";

  return (
    <div className="flex flex-col gap-5">
      <Field label={nameLabel}>{(id) => <TextInput id={id} required maxLength={120} value={value.name} onChange={(e) => set({ name: e.target.value })} placeholder="Clínica Andes" />}</Field>
      <Field label="A qué se dedica" hint="Los agentes la leen como contexto.">
        {(id) => <TextArea id={id} rows={3} maxLength={500} value={value.description} onChange={(e) => set({ description: e.target.value })} placeholder="Ej.: desarrollo de software a medida para pymes." />}
      </Field>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-xs font-medium text-fg-muted">Color base del tablero</legend>
        <div className="flex flex-wrap items-center gap-2">
          {BRAND_PRESETS.map((p) => (
            <label key={p.color} title={p.name} className="cursor-pointer rounded-full p-0.5 ring-2 ring-transparent has-[:checked]:ring-fg-strong has-[:focus-visible]:ring-fg-strong">
              <input type="radio" name="brand-color" value={p.color} checked={value.brandColor.toLowerCase() === p.color} onChange={() => set({ brandColor: p.color })} className="sr-only" aria-label={p.name} />
              <span className="block size-7 rounded-full" style={{ backgroundColor: p.color }} />
            </label>
          ))}
          <span className="mx-1 h-6 w-px bg-line" aria-hidden />
          <label className="flex items-center gap-2 text-xs text-fg-muted">
            <input type="color" value={validHex ? value.brandColor : "#ffb81c"} onChange={(e) => set({ brandColor: e.target.value })} aria-label="Otro color" className="size-8 cursor-pointer rounded-md border border-line-strong bg-transparent p-0.5" />
            <TextInput size="sm" value={value.brandColor} onChange={(e) => set({ brandColor: e.target.value.trim() })} maxLength={7} aria-label="Color en hexadecimal" invalid={!validHex} className="w-24 font-mono uppercase" />
          </label>
        </div>
        <p className="text-xs text-fg-faint">
          {validHex
            ? contrast(brand.color, "#ffffff") < 1.6
              ? "Color muy claro: en el tema claro se usará una versión más oscura."
              : "Se usa en botones principales, selección y foco."
            : "Escribe un color en formato #RRGGBB."}
        </p>
      </fieldset>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-xs font-medium text-fg-muted">Texto de los botones</legend>
        <div className="flex flex-wrap items-center gap-3">
          <div role="radiogroup" aria-label="Texto de los botones" className="inline-flex rounded-md border border-line-strong p-0.5">
            {[
              { id: "auto", label: "Automático", value: null },
              { id: "black", label: "Negro", value: "#0b1017" },
              { id: "white", label: "Blanco", value: "#ffffff" },
            ].map((o) => (
              <button
                key={o.id}
                type="button"
                role="radio"
                aria-checked={textMode === o.id}
                onClick={() => set({ brandOnColor: o.value })}
                className={clsx("rounded px-2.5 py-1 text-xs", textMode === o.id ? "bg-hover font-medium text-fg-strong" : "text-fg-muted hover:text-fg-strong")}
              >
                {o.label}
              </button>
            ))}
            <label className={clsx("flex cursor-pointer items-center gap-1.5 rounded px-2.5 py-1 text-xs", textMode === "custom" ? "bg-hover font-medium text-fg-strong" : "text-fg-muted hover:text-fg-strong")}>
              <input
                type="color"
                value={value.brandOnColor ?? autoOn(brand.color)}
                onChange={(e) => set({ brandOnColor: e.target.value })}
                aria-label="Otro color de texto"
                className="size-4 cursor-pointer rounded border-0 bg-transparent p-0"
              />
              Otro
            </label>
          </div>
          <span className="inline-flex items-center rounded-md px-3 py-1.5 text-[13px] font-medium" style={{ backgroundColor: brand.color, color: brand.on }}>
            + Crear issue
          </span>
          <span className={clsx("text-xs", ratio >= 4.5 ? "text-ok" : ratio >= 3 ? "text-warn" : "text-danger")}>
            Contraste {ratio.toFixed(1)}:1 · {ratio >= 4.5 ? "se lee bien" : ratio >= 3 ? "justo: mejor otro" : "no se lee"}
          </span>
        </div>
      </fieldset>

      <div className="flex flex-col gap-2">
        <span className="text-xs font-medium text-fg-muted">Logo</span>
        <div className="flex items-center gap-3">
          <div className="grid h-14 w-36 shrink-0 place-items-center overflow-hidden rounded-md border border-line-strong bg-sunken p-1.5">
            {value.logo ? <LogoMark src={value.logo} name="Logo" className="h-full max-w-full" /> : <ImageUp className="size-5 text-fg-faint" aria-hidden />}
          </div>
          <div className="flex flex-col gap-1">
            <div className="flex gap-2">
              <Button size="sm" icon={ImageUp} onClick={() => input.current?.click()}>
                {value.logo ? "Cambiar logo" : "Subir logo"}
              </Button>
              {value.logo && <Button size="sm" tone="ghost" icon={X} onClick={() => set({ logo: null })}>Quitar</Button>}
            </div>
            <p className={clsx("text-xs", logoError ? "text-danger" : "text-fg-faint")}>{logoError ?? "PNG, JPG o WebP. Se ajusta a 256 px."}</p>
          </div>
          <input
            ref={input}
            type="file"
            accept="image/png,image/jpeg,image/webp"
            className="sr-only"
            aria-label="Archivo del logo"
            onChange={async (e) => {
              const file = e.target.files?.[0];
              e.target.value = "";
              if (!file) return;
              try {
                const data = await shrink(file);
                if (data.length > MAX_LOGO) return setLogoError("La imagen sigue siendo muy pesada. Prueba con otra más simple.");
                setLogoError(null);
                set({ logo: data });
              } catch {
                setLogoError("No se pudo leer la imagen.");
              }
            }}
          />
        </div>
      </div>
    </div>
  );
}
