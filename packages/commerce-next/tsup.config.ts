// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from "tsup";

const external = ["react", "next", "next/navigation", "next/headers", /^@omnirec\//];

/**
 * Two builds: the client entry carries "use client" (bundling drops
 * module-level directives), the server entry must not.
 */
export default defineConfig([
  {
    entry: { index: "src/index.tsx" },
    format: ["esm", "cjs"],
    dts: true,
    clean: true,
    external,
    banner: { js: '"use client";' },
  },
  {
    entry: { server: "src/server.ts" },
    format: ["esm", "cjs"],
    dts: true,
    external,
  },
]);
