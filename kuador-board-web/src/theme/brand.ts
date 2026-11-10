/**
 * Marca del espacio: a partir de un solo color (#rrggbb) se deriva todo lo que el tablero necesita,
 * cuidando el contraste en los dos temas. Kuador usa su sol (#ffb81c); otra empresa, su rojo o su
 * verde, y el tablero sigue legible.
 */

export const DEFAULT_BRAND = "#ffb81c";

type Rgb = [number, number, number];

function toRgb(hex: string): Rgb {
  const h = /^#[0-9a-f]{6}$/i.test(hex) ? hex.slice(1) : DEFAULT_BRAND.slice(1);
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16)) as Rgb;
}

function toHex([r, g, b]: Rgb): string {
  return "#" + [r, g, b].map((v) => Math.round(Math.min(255, Math.max(0, v))).toString(16).padStart(2, "0")).join("");
}

/** Mezcla con blanco (amount > 0) o negro (amount < 0). */
function mix(rgb: Rgb, amount: number): Rgb {
  const target = amount > 0 ? 255 : 0;
  const t = Math.abs(amount);
  return rgb.map((c) => c + (target - c) * t) as Rgb;
}

function luminance([r, g, b]: Rgb): number {
  const lin = (v: number) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

export function contrast(a: string, b: string): number {
  const [l1, l2] = [luminance(toRgb(a)), luminance(toRgb(b))].sort((x, y) => y - x);
  return (l1 + 0.05) / (l2 + 0.05);
}

/** Oscurece (light) o aclara (dark) el color hasta que se lea como texto sobre ese fondo (AA 4.5:1). */
function readableOn(color: string, background: string, direction: -1 | 1): string {
  let rgb = toRgb(color);
  for (let i = 0; i < 40 && contrast(toHex(rgb), background) < 4.5; i++) rgb = mix(rgb, direction * 0.06);
  return toHex(rgb);
}

export interface Brand {
  /** Relleno de la acción primaria, selección y foco. */
  color: string;
  /** Hover del relleno. */
  hover: string;
  /** Texto e íconos encima del relleno (negro o blanco, el que contraste más). */
  on: string;
  /** El color de marca usado como texto sobre la superficie clara / oscura. */
  textLight: string;
  textDark: string;
  /** Escala 50…950 para el preset de PrimeReact. */
  scale: Record<50 | 100 | 200 | 300 | 400 | 500 | 600 | 700 | 800 | 900 | 950, string>;
}

const SURFACE_LIGHT = "#ffffff";
const SURFACE_DARK = "#10151d";

/** Texto automático encima del color: negro o blanco, el que más contraste. */
export function autoOn(color: string): string {
  return contrast(color, "#0b1017") >= contrast(color, "#ffffff") ? "#0b1017" : "#ffffff";
}

/** {@code onColor}: el texto de los botones elegido por la empresa; nulo = automático. */
export function brandFrom(hex: string | null | undefined, onColor?: string | null): Brand {
  const base = toRgb(hex ?? DEFAULT_BRAND);
  const color = toHex(base);
  const on = onColor && /^#[0-9a-f]{6}$/i.test(onColor) ? onColor.toLowerCase() : autoOn(color);
  return {
    color,
    // Con texto claro, el hover oscurece el relleno; con texto oscuro, lo aclara.
    hover: toHex(mix(base, luminance(toRgb(on)) > 0.5 ? -0.12 : 0.18)),
    on,
    textLight: readableOn(color, SURFACE_LIGHT, -1),
    textDark: readableOn(color, SURFACE_DARK, 1),
    scale: {
      50: toHex(mix(base, 0.92)),
      100: toHex(mix(base, 0.8)),
      200: toHex(mix(base, 0.62)),
      300: toHex(mix(base, 0.42)),
      400: toHex(mix(base, 0.2)),
      500: color,
      600: toHex(mix(base, -0.14)),
      700: toHex(mix(base, -0.32)),
      800: toHex(mix(base, -0.5)),
      900: toHex(mix(base, -0.66)),
      950: toHex(mix(base, -0.8)),
    },
  };
}

/** Variables CSS que la hoja global usa para el acento (ver globals.css). */
export function brandVars(brand: Brand): Record<string, string> {
  return {
    "--brand": brand.color,
    "--brand-hover": brand.hover,
    "--brand-on": brand.on,
    "--brand-text-light": brand.textLight,
    "--brand-text-dark": brand.textDark,
  };
}

/** Colores sugeridos en el onboarding (se puede elegir cualquier otro). */
export const BRAND_PRESETS = [
  { name: "Ámbar", color: "#ffb81c" },
  { name: "Rojo", color: "#e11d48" },
  { name: "Naranja", color: "#f97316" },
  { name: "Verde", color: "#16a34a" },
  { name: "Turquesa", color: "#0d9488" },
  { name: "Azul", color: "#2563eb" },
  { name: "Índigo", color: "#4f46e5" },
  { name: "Violeta", color: "#9333ea" },
];
