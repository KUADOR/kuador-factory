import { definePreset } from "@primeuix/themes";
import Aura from "@primeuix/themes/aura";

import type { Brand } from "./brand";

/**
 * Tema Kuador para PrimeReact: noche técnica con un solo sol (ver landing/kuador-app/DESIGN.md).
 * Un único preset sirve para los dos temas: cada token usa light-dark(claro, oscuro) y el modo lo
 * decide la clase `app-dark` en <html>. Las utilidades de Tailwind leen estas mismas variables
 * (--p-*) desde globals.css, así que componentes Prime y maquetación propia nunca se desalinean.
 */
const sol = {
  50: "#fff8e6",
  100: "#ffefc2",
  200: "#ffe08a",
  300: "#ffd66b",
  400: "#ffc53d",
  500: "#ffb81c",
  600: "#e09a00",
  700: "#a86f00",
  800: "#7d5300",
  900: "#553800",
  950: "#332200",
};

/** Claro: grises neutros, sin tinte. Oscuro: la noche Kuador (página #0a0e14, superficie #10151d). */
const surface = {
  0: "#ffffff",
  50: "light-dark(#fafafa, #eef2f5)",
  100: "light-dark(#f4f4f5, #c6d2db)",
  200: "light-dark(#e7e7ea, #aab6c0)",
  300: "light-dark(#d8d8dc, #94a3af)",
  400: "light-dark(#a8a8b0, #75838f)",
  500: "light-dark(#6e6e78, #4a5461)",
  600: "light-dark(#5a5a63, #2a3644)",
  700: "light-dark(#3f3f46, #1e2833)",
  800: "light-dark(#2b2b30, #151c26)",
  900: "light-dark(#18181b, #10151d)",
  950: "light-dark(#0c0c0e, #0a0e14)",
};

/** Preset con la marca del espacio: reemplaza la escala del acento y el color sobre el acento. */
export function brandedPreset(brand: Brand) {
  return definePreset(KuadorPreset, {
    semantic: { primary: { ...brand.scale, color: "{primary.500}", contrastColor: brand.on } },
  });
}

export const KuadorPreset = definePreset(Aura, {
  semantic: {
    primary: {
      ...sol,
      color: "{primary.500}",
      contrastColor: "#0b1017",
      hoverColor: "light-dark({primary.600}, {primary.400})",
      activeColor: "light-dark({primary.700}, {primary.300})",
    },
    surface,
    focusRing: { width: "2px", style: "solid", color: "{primary.500}", offset: "2px", shadow: "none" },
    highlight: {
      background: "light-dark({primary.100}, color-mix(in srgb, {primary.500}, transparent 86%))",
      focusBackground: "light-dark({primary.200}, color-mix(in srgb, {primary.500}, transparent 78%))",
      color: "light-dark({primary.800}, {primary.300})",
      focusColor: "light-dark({primary.900}, {primary.200})",
    },
    text: {
      color: "light-dark({surface.800}, {surface.100})",
      hoverColor: "light-dark({surface.950}, #ffffff)",
      mutedColor: "light-dark({surface.600}, {surface.300})",
      hoverMutedColor: "light-dark({surface.700}, {surface.200})",
    },
    content: {
      background: "light-dark(#ffffff, {surface.900})",
      hoverBackground: "light-dark({surface.100}, {surface.700})",
      borderColor: "light-dark({surface.200}, {surface.700})",
    },
    formField: {
      paddingX: "0.625rem",
      paddingY: "0.375rem",
      background: "light-dark(#ffffff, {surface.950})",
      disabledBackground: "light-dark({surface.100}, {surface.800})",
      borderColor: "light-dark({surface.300}, {surface.600})",
      hoverBorderColor: "light-dark({surface.400}, {surface.500})",
      color: "light-dark({surface.900}, #ffffff)",
      placeholderColor: "light-dark({surface.500}, {surface.400})",
      iconColor: "light-dark({surface.500}, {surface.400})",
      shadow: "none",
    },
    overlay: {
      select: { background: "light-dark(#ffffff, {surface.800})", borderColor: "light-dark({surface.200}, {surface.600})" },
      popover: { background: "light-dark(#ffffff, {surface.800})", borderColor: "light-dark({surface.200}, {surface.600})" },
      modal: { background: "light-dark(#ffffff, {surface.900})", borderColor: "light-dark({surface.200}, {surface.700})" },
    },
    list: {
      option: {
        focusBackground: "light-dark({surface.100}, {surface.700})",
      },
    },
    navigation: {
      item: {
        focusBackground: "light-dark({surface.100}, {surface.700})",
        activeBackground: "light-dark({surface.100}, {surface.700})",
      },
    },
    mask: { background: "light-dark(rgba(11,16,23,0.35), rgba(3,5,8,0.7))" },
  },
});
