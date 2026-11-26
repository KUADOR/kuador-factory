import { expect, test } from "@playwright/test";

import { board, card, cardIn, subject, team, teamBoard } from "./api";
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

test("el tablero suma un proyecto desde Organización y su configuración queda solo con el flujo", async ({ page }) => {
  const { key } = await board("CFG", "Desarrollo del equipo");
  const reservas = await subject("Sistema de reservas");

  // La configuración del tablero nombra a su equipo y lleva a Organización para cambiarlo.
  await page.goto(`/projects/${key}/settings/general`);
  await expect(page.getByText("Lo atiende")).toBeVisible();
  await page.getByRole("link", { name: "Cambiar" }).click();
  await expect(page).toHaveURL(new RegExp(`/org/boards\\?tablero=${key}`));

  // Allí se le suma el proyecto; con dos ya aparece el selector en el encabezado del tablero.
  await page.getByRole("combobox", { name: "Proyectos del tablero" }).click();
  await page.getByRole("option", { name: reservas.name }).click();
  await expect(page.getByRole("button", { name: `Quitar ${reservas.name}` })).toBeVisible();
  await page.goto(`/projects/${key}`);
  await expect(page.getByRole("combobox", { name: "Proyecto", exact: true })).toBeVisible();
});

test("un tablero nuevo responde tres preguntas: cómo se trabaja, qué equipo y qué proyectos", async ({ page }) => {
  const letters = Array.from({ length: 4 }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join("");
  const crew = await team("Ventas");
  await page.goto("/org/boards?nuevo=1");
  await page.getByLabel("Nombre").fill(`Ventas ${letters}`);
  await page.getByLabel("Clave").fill(`V${letters}`);
  await detalle(page).getByText("Tareas simples", { exact: true }).click();
  await page.getByRole("combobox", { name: "¿Qué equipo lo atiende?" }).click();
  await page.getByRole("option", { name: crew.name }).click();
  await page.getByRole("button", { name: "Crear tablero" }).click();
  await expect(page).toHaveURL(new RegExp(`/projects/V${letters}`));
  await page.goto(`/projects/V${letters}/settings/general`);
  await expect(page.getByText(crew.name)).toBeVisible();
});
