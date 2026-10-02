// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from "tsup";

export default defineConfig({
  entry: ["src/index.tsx"],
  format: ["esm", "cjs"],
  dts: true,
  clean: true,
  external: ["react", "@omnirec/commerce-web"],
  // Bundling drops module-level directives; every export here is client-side React.
  banner: { js: '"use client";' },
});
