"use client";

import { Moon, Sun } from "lucide-react";
import { useState } from "react";

import { setThemeAction } from "@/app/actions";
import type { ThemeMode } from "@/theme/theme";

/** Cambia entre oscuro y claro al instante; la cookie lo recuerda para el siguiente render del servidor. */
export function ThemeToggle({ initial }: { initial: ThemeMode }) {
  const [mode, setMode] = useState<ThemeMode>(initial);
  const next: ThemeMode = mode === "dark" ? "light" : "dark";
  return (
    <button
      type="button"
      onClick={() => {
        document.documentElement.classList.toggle("app-dark", next === "dark");
        setMode(next);
        void setThemeAction(next);
      }}
      aria-label={next === "light" ? "Usar tema claro" : "Usar tema oscuro"}
      title={next === "light" ? "Tema claro" : "Tema oscuro"}
      className="grid size-8 place-items-center rounded-md text-fg-muted hover:bg-hover hover:text-fg-strong"
    >
      {mode === "dark" ? <Sun className="size-4" aria-hidden /> : <Moon className="size-4" aria-hidden />}
    </button>
  );
}
