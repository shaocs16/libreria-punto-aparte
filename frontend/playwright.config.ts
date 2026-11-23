import { defineConfig } from "@playwright/test";

// Tres anchos como en la guía de diseño: escritorio, tablet y móvil.
export default defineConfig({
  testDir: "./tests/e2e",
  testMatch: "**/*.spec.ts",
  timeout: 45_000,
  workers: 1,
  fullyParallel: false,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:8080",
    browserName: "chromium",
    // En redes con proxy TLS corporativo `playwright install` puede fallar: PW_CHANNEL=msedge usa el Edge instalado.
    channel: process.env.PW_CHANNEL || undefined,
    headless: true,
    locale: "es-ES",
    timezoneId: "Europe/Madrid",
  },
  webServer: {
    command: "node tests/e2e/start-server.mjs",
    url: "http://localhost:8080/api/v1/health",
    reuseExistingServer: false,
    timeout: 120_000,
  },
  projects: [
    { name: "desktop", use: { viewport: { width: 1440, height: 1000 } } },
    { name: "tablet", use: { viewport: { width: 768, height: 1024 } } },
    {
      name: "mobile",
      use: {
        viewport: { width: 390, height: 844 },
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
});
