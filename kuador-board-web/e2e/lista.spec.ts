import { expect, test } from "@playwright/test";

import { board, card } from "./api";
import { detalle } from "./pantalla";

test("la lista busca, filtra, ordena y abre la tarjeta", async ({ page }) => {
  const { key, columns } = await board("LIS", "Lista de prueba");
  const first = columns[0];
  const done = columns.find((c) => c.category === "DONE")!;
  await card(key, { title: "Zapatos devueltos", status: first.key, labels: ["urgente"] });
  await card(key, { title: "Abrigo entregado", status: done.key });
  await card(key, { title: "Mochila entregada", status: done.key, labels: ["urgente"] });

  await page.goto(`/projects/${key}`);
  await page.getByRole("navigation", { name: "Vista" }).getByRole("link", { name: "Lista" }).click();
  await expect(page).toHaveURL(/view=list/);
  const rows = page.locator("tbody tr");
  await expect(rows).toHaveCount(3);

  // Ordenar por título.
  await page.getByRole("link", { name: "Título" }).click();
  await expect(page).toHaveURL(/sort=title/);
  await expect(rows.first()).toContainText("Abrigo entregado");
  await page.getByRole("link", { name: "Título" }).click();
  await expect(page).toHaveURL(/dir=desc|sort=title(?!.*dir=asc)/);
  await expect(rows.first()).toContainText("Zapatos devueltos");

  // Buscar: queda en la URL.
  await page.getByPlaceholder("Buscar por título o clave").fill("entreg");
  await expect(page).toHaveURL(/q=entreg/);
  await expect(rows).toHaveCount(2);

  // Filtrar por etiqueta con un clic.
  await rows.filter({ hasText: "Mochila" }).getByRole("link", { name: "urgente" }).click();
  await expect(page).toHaveURL(/label=urgente/);
  await expect(rows).toHaveCount(1);

  // Limpiar y abrir una tarjeta.
  await page.getByRole("link", { name: "Limpiar filtros" }).click();
  await expect(rows).toHaveCount(3);
  await rows.filter({ hasText: "Abrigo entregado" }).click();
  await expect(detalle(page).getByText(`${key}-2`)).toBeVisible();
});
