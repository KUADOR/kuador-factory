import type { Locator, Page } from "@playwright/test";

/** El panel de una tarjeta abierta (el que tiene el botón «Cerrar»). */
export function detalle(page: Page): Locator {
  return page.getByRole("complementary").filter({ has: page.getByRole("button", { name: "Cerrar", exact: true }) });
}
