import { expect, test } from "@playwright/test";

import { EMAIL, PASSWORD } from "./api";

test("sin sesión, un tablero lleva a iniciar sesión y vuelve a él al entrar", async ({ page }) => {
  await page.goto("/projects");
  await expect(page).toHaveURL(/\/login/);

  await page.locator('input[type="email"]').fill(EMAIL);
  await page.locator('input[type="password"]').fill("una-clave-equivocada");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("alert").or(page.getByText(/incorrect|no coincide|inválid/i)).first()).toBeVisible();
  await expect(page).toHaveURL(/\/login/);

  await page.locator('input[type="password"]').fill(PASSWORD);
  await page.getByRole("button", { name: "Entrar", exact: true }).click();
  await expect(page).not.toHaveURL(/\/login/);
});
