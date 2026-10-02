// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from "tsup";

/**
 * The core and each plugin are separate entries, so a page that imports only
 * `createOmnirec` never downloads the plugins it does not use. ESM output
 * shares code between entries through chunks.
 */
export default defineConfig([
  {
  entry: {
    index: "src/index.ts",
    dom: "src/plugins/dom.ts",
    impressions: "src/plugins/impressions.ts",
    autocapture: "src/plugins/autocapture.ts",
    consent: "src/plugins/consent.ts",
    debug: "src/plugins/debug.ts",
  },
  format: ["esm", "cjs"],
  dts: true,
  clean: true,
  splitting: true,
  },
  {
    // Script-tag build: core + dom + impressions + autocapture, one file.
    entry: { omnirec: "src/browser.ts" },
    format: ["iife"],
    minify: true,
    platform: "browser",
    target: "es2019",
    outExtension: () => ({ js: ".min.js" }),
  },
]);
