/** Tema de la interfaz. Oscuro por defecto; la elección vive en una cookie para pintarla desde el servidor. */
export const THEME_COOKIE = "kuador_theme";
export type ThemeMode = "dark" | "light";

export function parseTheme(value: string | undefined): ThemeMode {
  return value === "light" ? "light" : "dark";
}
