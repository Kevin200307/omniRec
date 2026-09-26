// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // jsdom gives us document.cookie, localStorage, and visibilitychange —
    // the browser surfaces identity and dwell tracking are built on.
    environment: "jsdom",
    globals: true,
    include: ["tests/**/*.test.ts"],
    restoreMocks: true,
  },
});
