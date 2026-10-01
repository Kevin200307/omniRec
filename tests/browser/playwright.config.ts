// SPDX-License-Identifier: Apache-2.0
import { defineConfig, devices } from "@playwright/test";

// Port chosen to stay clear of the other local stacks (8080, 8124, 5432, ...).
const PORT = Number(process.env.OMNIREC_BROWSER_TEST_PORT ?? 4317);

/**
 * Real-browser tests for the SDKs. jsdom covers logic; these cover what jsdom
 * cannot: real IntersectionObserver, sendBeacon, event delegation, and the
 * built bundle as a merchant would load it.
 *
 * Prerequisite: `npx turbo run build --filter=@omnirec/commerce-web`.
 */
export default defineConfig({
  testDir: ".",
  testMatch: "**/*.spec.ts",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `node server.mjs ${PORT}`,
    cwd: __dirname,
    url: `http://localhost:${PORT}/health`,
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
});
