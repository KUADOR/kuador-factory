import { expect, test } from "@playwright/test";

import { board } from "./api";

test("cada pantalla tiene su lugar y dice de dónde viene lo que muestra", async ({ page }) => {
  const { key } = await board("ORI", "Orientación", "SOFTWARE_FACTORY");

  // El pulso es una vista de Inicio, no una página suelta.
  await page.goto("/pulse");
  await expect(page).toHaveURL(/\?view=pulse$/);
  await expect(page.getByRole("navigation", { name: "Vista" }).getByRole("link", { name: "Pulso del día" })).toHaveAttribute("aria-current", "page");

  // La oficina es una pestaña del tablero.
  await page.goto(`/projects/${key}`);
  await page.getByRole("navigation", { name: "Secciones del tablero" }).getByRole("link", { name: "Oficina" }).click();
  await expect(page).toHaveURL(/\/office$/);

  // Conexiones: primero el resumen, después el detalle de una, con regreso.
  await page.goto("/connections");
  for (const name of ["Inteligencia artificial", "GitHub", "Sistemas de la empresa", "Telegram"]) {
    await expect(page.getByRole("link", { name: new RegExp(name) })).toBeVisible();
  }
  await page.getByRole("link", { name: /Telegram/ }).click();
  await expect(page).toHaveURL(/ver=telegram/);
  await expect(page.getByRole("link", { name: "Conexiones" }).first()).toBeVisible();

  // El catálogo dice el origen de cada entrada y cuánto se usa.
  await page.goto("/catalog/roles");
  await expect(page.getByRole("columnheader", { name: "Origen" })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "Miembros" })).toBeVisible();
  await expect(page.getByText("De fábrica").first()).toBeVisible();
});
