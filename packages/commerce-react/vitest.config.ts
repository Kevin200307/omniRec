// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // The provider wraps @omnirec/commerce-web, which needs document.cookie
    // and localStorage for identity.
    environment: "jsdom",
    globals: true,
    include: ["tests/**/*.test.{ts,tsx}"],
    restoreMocks: true,
  },
});
