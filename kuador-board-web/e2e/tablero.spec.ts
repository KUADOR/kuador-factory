import { expect, test } from "@playwright/test";

import { board, card } from "./api";
import { detalle } from "./pantalla";

test("crear una tarjeta, abrirla, comentarla y verla terminada", async ({ page }) => {
  const { key, columns } = await board("TAB", "Tablero de prueba");
  const first = columns[0];
  const done = columns.find((c) => c.category === "DONE")!;
  await card(key, { title: "Llamar al proveedor", status: first.key });

  await page.goto(`/projects/${key}`);
  const pending = page.locator(`[data-column="${first.key}"]`);
  await expect(pending.getByText("Llamar al proveedor")).toBeVisible();

  // Crear desde el tablero.
  await page.getByRole("button", { name: "Crear tarjeta" }).first().click();
  await page.getByPlaceholder("¿Qué hay que hacer?").fill("Preparar la cotización");
  await page.getByRole("button", { name: "Crear tarjeta" }).last().click();
  await expect(pending.getByText("Preparar la cotización")).toBeVisible();

  // Abrirla: el detalle queda en la URL, para compartirla.
  await pending.getByText("Preparar la cotización").click();
  await expect(page).toHaveURL(new RegExp(`issue=${key}-2`));
  const detail = detalle(page);
  await expect(detail.getByText(`${key}-2`)).toBeVisible();

  // Comentar.
  await detail.getByPlaceholder(/Escribe un comentario/).fill("El cliente la quiere para el viernes.");
  await detail.getByRole("button", { name: "Comentar" }).click();
  await expect(detail.getByText("El cliente la quiere para el viernes.")).toBeVisible();
  await detail.getByRole("button", { name: "Cerrar", exact: true }).click();
  await expect(page).not.toHaveURL(/issue=/);

  // Lo que cambia por la API aparece solo (tablero en vivo), sin recargar.
  await card(key, { title: "Cobro confirmado", status: done.key });
  await expect(page.locator(`[data-column="${done.key}"]`).getByText("Cobro confirmado")).toBeVisible();
});

test("el paso final muestra las últimas 20 y lleva a la lista", async ({ page }) => {
  const { key, columns } = await board("FIN", "Muchas terminadas");
  const done = columns.find((c) => c.category === "DONE")!;
  for (let i = 1; i <= 23; i++) await card(key, { title: `Pedido ${String(i).padStart(2, "0")}`, status: done.key });

  await page.goto(`/projects/${key}`);
  const column = page.locator(`[data-column="${done.key}"]`);
  await expect(column.locator("header")).toContainText("20/23");
  await expect(column.getByText("Pedido 23")).toBeVisible();
  await expect(column.getByText("Pedido 03", { exact: true })).toHaveCount(0);

  await column.getByRole("link", { name: /Ver todas en la lista/ }).click();
  await expect(page).toHaveURL(new RegExp(`view=list.*status=${done.key}`));
  await expect(page.getByRole("status").filter({ hasText: "de 23" })).toBeVisible();

  // Una terminada que el tablero no trae igual se abre por su enlace.
  await page.goto(`/projects/${key}?issue=${key}-1`);
  await expect(detalle(page).getByText(`${key}-1`)).toBeVisible();
});
