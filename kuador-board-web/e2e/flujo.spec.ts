import { expect, test } from "@playwright/test";

import { board } from "./api";

test("la fábrica de software muestra quién trabaja cada etapa y en qué orden", async ({ page }) => {
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

  await tabs.getByRole("link", { name: "Ajustes" }).click();
  await expect(page).toHaveURL(/\/settings/);
  await page.goto(`/projects/${key}/settings/stages`);
  await expect(page.getByText("Diseño UI/UX").first()).toBeVisible();
});
