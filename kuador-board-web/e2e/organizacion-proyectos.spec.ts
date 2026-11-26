import { expect, test } from "@playwright/test";

import { subject, teamBoard } from "./api";

test("un proyecto se configura una vez en Organización y su tablero lo usa", async ({ page }) => {
  const gym = await subject("Gimnasio");
  const { key } = await teamBoard("GYD", "Desarrollo gimnasio", [gym.key], "SOFTWARE");

  // Se crea desde Organización y se abre con lo suyo.
  const letters = Array.from({ length: 4 }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join("");
  await page.goto("/org/projects");
  await page.getByRole("button", { name: "Nuevo proyecto" }).click();
  await page.getByLabel("Nombre").fill(`Reservas ${letters}`);
  await page.getByRole("button", { name: "Crear proyecto" }).click();
  await expect(page.getByRole("heading", { name: `Reservas ${letters}` })).toBeVisible();
  await expect(page.getByText("Ningún tablero lo trabaja todavía.")).toBeVisible();

  // El manual se elige en el proyecto y vale en su tablero.
  await page.goto(`/org/projects/${gym.key}`);
  await expect(page.getByText("Lo usan Desarrollo gimnasio.")).toBeVisible();
  await page.getByText("ODD", { exact: true }).click();
  await page.getByRole("button", { name: "Guardar metodología" }).click();
  await expect(page.getByRole("link", { name: "Ir a los documentos" })).toBeVisible();

  // Un documento base creado en el proyecto se ve desde el tablero: es el mismo.
  await page.getByRole("link", { name: "Documentos base" }).click();
  await page.getByRole("button", { name: "Crear" }).first().click();
  await expect(page).toHaveURL(new RegExp(`/org/projects/${gym.key}/docs/[0-9a-f-]{36}$`));
  const id = page.url().split("/").pop();
  await page.goto(`/projects/${key}/docs`);
  await expect(page.locator(`a[href="/projects/${key}/docs/${id}"]`)).toBeVisible();
});
