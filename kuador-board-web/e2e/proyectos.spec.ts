import { expect, test } from "@playwright/test";

import { board, card, cardIn, subject, teamBoard } from "./api";
import { detalle } from "./pantalla";

test("un tablero de equipo trabaja en varios proyectos: etiqueta, filtro y proyecto al crear", async ({ page }) => {
  const kuador = await subject("Kuador");
  const medica = await subject("Kuador Médica");
  const { key, columns } = await teamBoard("MKT", "Marketing", [kuador.key, medica.key]);
  const first = columns[0];
  await cardIn(key, kuador.key, { title: "Campaña de diciembre", status: first.key });
  await cardIn(key, medica.key, { title: "Folleto para clínicas", status: first.key });

  await page.goto(`/projects/${key}`);
  const column = page.locator(`[data-column="${first.key}"]`);
  // Cada tarjeta dice de qué proyecto es.
  await expect(column.getByText(kuador.name)).toBeVisible();
  await expect(column.getByText(medica.name)).toBeVisible();

  // El proyecto elegido filtra el tablero y se recuerda al volver.
  await page.getByRole("combobox", { name: "Proyecto" }).click();
  await page.getByRole("option", { name: medica.name }).click();
  await expect(column.getByText("Campaña de diciembre")).toHaveCount(0);
  await expect(column.getByText("Folleto para clínicas")).toBeVisible();
  await page.reload();
  await expect(column.getByText("Folleto para clínicas")).toBeVisible();
  await expect(column.getByText("Campaña de diciembre")).toHaveCount(0);

  // Una tarjeta nueva nace en el proyecto que se está mirando.
  await page.getByRole("button", { name: "Crear tarjeta" }).first().click();
  await page.getByPlaceholder("¿Qué hay que hacer?").fill("Video testimonial");
  await page.getByRole("button", { name: "Crear tarjeta" }).last().click();
  await expect(column.getByText("Video testimonial")).toBeVisible();

  // Con «Todos» se ven las de los dos, cada una con su etiqueta; al abrir una se ve y se puede cambiar su proyecto.
  await page.getByRole("combobox", { name: "Proyecto" }).click();
  await page.getByRole("option", { name: "Todos los proyectos" }).click();
  await expect(column.getByText("Campaña de diciembre")).toBeVisible();
  await expect(column.getByText(medica.name)).toHaveCount(2);
  await expect(column.getByText(kuador.name)).toHaveCount(1);
  await column.getByText("Video testimonial").click();
  await expect(detalle(page).getByText("Proyecto", { exact: true })).toBeVisible();
});

test("con un solo proyecto no aparece nada nuevo", async ({ page }) => {
  const { key, columns } = await board("UNO", "Un solo proyecto");
  await card(key, { title: "Tarea de siempre", status: columns[0].key });
  await page.goto(`/projects/${key}`);
  await expect(page.locator(`[data-column="${columns[0].key}"]`).getByText("Tarea de siempre")).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Proyecto" })).toHaveCount(0);
  // La etiqueta del proyecto (su nombre es el del tablero) tampoco se repite en la tarjeta.
  await expect(page.locator(`[data-column="${columns[0].key}"]`).getByText("Un solo proyecto")).toHaveCount(0);
});

test("sumar un proyecto nuevo desde la configuración del tablero", async ({ page }) => {
  const { key } = await board("CFG", "Desarrollo del equipo");
  await page.goto(`/projects/${key}/settings/general`);
  await page.getByLabel("Nombre del proyecto nuevo").fill("Sistema de reservas");
  await page.getByRole("button", { name: "Crear", exact: true }).click();
  await expect(page.getByRole("listitem").filter({ hasText: "Sistema de reservas" })).toBeVisible();
  // Con dos proyectos ya aparece el selector en el encabezado del tablero.
  await expect(page.getByRole("combobox", { name: "Proyecto", exact: true })).toBeVisible();
});
