import { expect, test } from "@playwright/test";

import { board } from "./api";

test("la fábrica de software muestra quién trabaja cada paso y en qué orden", async ({ page }) => {
  const { key, columns } = await board("FAB", "Fábrica de prueba", "SOFTWARE_FACTORY");
  expect(columns.map((c) => c.key)).toContain("DESIGN");

  // En Diseño, las épicas las trabaja primero UI/UX y después el Arquitecto.
  await page.goto(`/projects/${key}`);
  await expect(page.locator('[data-column="DESIGN"]')).toContainText("Diseño UI/UX → Arquitecto de software");

  // Las secciones del tablero cargan.
  const tabs = page.getByRole("navigation", { name: "Secciones del tablero" });
  await tabs.getByRole("link", { name: "Flujo" }).click();
  await expect(page).toHaveURL(/\/flow$/);
  await expect(page.getByText("Diseño").first()).toBeVisible();

  // Un paso se cambia aquí mismo, con todo lo suyo: no hay otra pantalla para el flujo.
  await page.getByRole("button", { name: /^Diseño/ }).first().click();
  await expect(page.getByText("Quién lo hace")).toBeVisible();
  await page.getByText("Más opciones").click();
  await expect(page.getByText("En épicas, antes trabaja")).toBeVisible();
  await expect(page.getByText("Diseño UI/UX").first()).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("button", { name: "Agregar, ordenar o quitar pasos" })).toBeVisible();

  // La configuración queda en cuatro secciones; las rutas viejas del flujo llevan a la pestaña Flujo.
  await tabs.getByRole("link", { name: "Configuración" }).click();
  await expect(page).toHaveURL(/\/settings/);
  const secciones = page.getByRole("navigation", { name: "Configuración del tablero" });
  await expect(secciones.getByRole("link")).toHaveText(["General", "Quién trabaja aquí", "Tarjeta", "Entradas"]);
  await page.goto(`/projects/${key}/settings/stages`);
  await expect(page).toHaveURL(/\/flow$/);
});
