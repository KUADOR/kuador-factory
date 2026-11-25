import { expect, test as setup } from "@playwright/test";

import { EMAIL, PASSWORD } from "./api";

/** Entra una vez y guarda la sesión: las demás pruebas arrancan ya dentro. */
setup("entrar y guardar la sesión", async ({ page }) => {
  await page.goto("/login");
  await page.locator('input[type="email"]').fill(EMAIL);
  await page.locator('input[type="password"]').fill(PASSWORD);
  await page.keyboard.press("Enter");
  await expect(page).not.toHaveURL(/\/login/);
  await page.context().storageState({ path: "e2e/resultados/sesion.json" });
});
