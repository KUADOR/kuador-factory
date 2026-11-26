import { expect, test } from "@playwright/test";

import { assignTeam, board, team } from "./api";
import { detalle } from "./pantalla";

test("un equipo se arma una vez en Organización y atiende varios tableros", async ({ page }) => {
  const dev = await board("EQD", "Desarrollo del equipo");
  const sop = await board("EQS", "Soporte del equipo");
  const letters = Array.from({ length: 4 }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join("");
  const name = `team ${letters}`;

  // Se llega desde el menú y se crea con su nombre.
  await page.goto("/");
  await page.getByRole("link", { name: "Organización" }).click();
  await expect(page.getByRole("heading", { name: "Equipos" })).toBeVisible();
  await page.getByRole("button", { name: "Nuevo equipo" }).click();
  await page.getByLabel("Nombre").fill(name);
  await page.getByRole("button", { name: "Crear equipo" }).click();

  // Al crearlo se abre: se suma una persona y nace sin tableros, así que se puede borrar.
  const panel = detalle(page);
  await expect(panel.getByText("Todavía no atiende ningún tablero.")).toBeVisible();
  await panel.getByRole("combobox", { name: "Sumar una persona" }).click();
  await page.getByRole("option").first().click();
  await expect(panel.getByRole("button", { name: /Quitar a .* del equipo/ }).first()).toBeVisible();
  await expect(panel.getByRole("button", { name: "Borrar equipo" })).toBeVisible();

  // Cuando atiende dos tableros, el panel los nombra y ya no se borra.
  const listed = await team("Soporte y desarrollo");
  await assignTeam(listed.id, dev.key);
  await assignTeam(listed.id, sop.key);
  await page.goto(`/org/teams?equipo=${listed.id}`);
  await expect(detalle(page).getByRole("link", { name: "Desarrollo del equipo" })).toBeVisible();
  await expect(detalle(page).getByRole("link", { name: "Soporte del equipo" })).toBeVisible();
  await expect(detalle(page).getByRole("button", { name: "Borrar equipo" })).toHaveCount(0);
});
