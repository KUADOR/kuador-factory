import { defineConfig } from "@playwright/test";

/**
 * Pruebas de punta a punta: la web de verdad contra la API de verdad y una base desechable. El entorno lo levanta
 * `e2e/entorno.sh` (también en el CI), que deja las direcciones en E2E_BASE_URL y E2E_API_URL.
 */
export default defineConfig({
  testDir: "e2e",
  outputDir: "e2e/.resultados",
  // Comparten una sola base: en orden, para que lo que una prueba crea no cambie lo que otra cuenta.
  workers: 1,
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [["github"], ["list"]] : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://127.0.0.1:13600",
    channel: process.env.E2E_CHANNEL || undefined,
    locale: "es-EC",
    viewport: { width: 1440, height: 900 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "sesion", testMatch: /sesion\.setup\.ts/ },
    { name: "web", dependencies: ["sesion"], use: { storageState: "e2e/.resultados/sesion.json" }, testIgnore: /sesion\.setup\.ts|entrar\.spec\.ts/ },
    { name: "entrar", testMatch: /entrar\.spec\.ts/ },
  ],
});
