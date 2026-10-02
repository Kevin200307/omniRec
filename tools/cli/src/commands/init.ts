// SPDX-License-Identifier: Apache-2.0
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

export type Framework = "next" | "react" | "vue" | "web" | "spring" | "html";

export interface InitOptions {
  cwd?: string;
  /** Overwrite an existing omnirec.plan.yaml. */
  force?: boolean;
  /** Skip detection. */
  framework?: Framework;
  /** Collector URL for the snippets. Default: the local `omnirec dev` collector. */
  endpoint?: string;
  log?: (line: string) => void;
}

export const PLAN_TEMPLATE = `# omniRec tracking plan: your own events, on top of the standard catalog.
# The catalog already has ~200 commerce events (product_viewed, add_to_cart,
# purchase_completed, ...): \`omnirec dev\` serves them at /v1/catalog. Add an
# event here only when none fits.
#
#   omnirec validate     check this file (CI: --against origin/main)
#   omnirec generate     types for track() / constants for Java
#   omnirec dev          local collector that validates against catalog + plan
#
# The collector loads this file too (omnirec.events.tenants.<id>.plan-paths).

vocabularies:
  size_unit: [cm, inch]

events:
  size_guide_opened:
    description: "Shopper opened the size guide on a product page."
    sources: [browser]
    blocks: [product]
    properties:
      product.id: { required: true }
      unit: { type: enum, vocabulary: size_unit }
`;

/** What kind of project `dir` is, from its build files. */
export function detectFramework(dir: string): Framework {
  const pkgPath = join(dir, "package.json");
  if (existsSync(pkgPath)) {
    let deps: Record<string, string> = {};
    try {
      const pkg = JSON.parse(readFileSync(pkgPath, "utf8"));
      deps = { ...pkg.dependencies, ...pkg.devDependencies, ...pkg.peerDependencies };
    } catch {
      // unreadable package.json: treat as a plain JS project
    }
    if (deps.next) return "next";
    if (deps.vue || deps.nuxt) return "vue";
    if (deps.react) return "react";
    return "web";
  }
  // Any JVM build: the Spring starter is the server-side SDK; plain Java uses omnirec-tracker-java.
  if (["pom.xml", "build.gradle", "build.gradle.kts"].some((build) => existsSync(join(dir, build)))) return "spring";
  return "html";
}

const LABEL: Record<Framework, string> = {
  next: "Next.js",
  react: "React",
  vue: "Vue",
  web: "JavaScript / TypeScript",
  spring: "Spring Boot",
  html: "plain HTML (script tag)",
};

/** The install command and code to paste, per framework. */
export function snippet(framework: Framework, endpoint: string): string {
  switch (framework) {
    case "next":
      return `npm install @omnirec/commerce-next

// app/layout.tsx
import { OmnirecNextProvider } from "@omnirec/commerce-next";
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html><body><OmnirecNextProvider endpoint="${endpoint}">{children}</OmnirecNextProvider></body></html>;
}

// any client component
import { useTrack } from "@omnirec/commerce-react";
const track = useTrack();
track("product_added_to_cart", { product: { id: product.id, quantity: 1 } });`;
    case "react":
      return `npm install @omnirec/commerce-react

import { OmnirecProvider, useTrack } from "@omnirec/commerce-react";
<OmnirecProvider endpoint="${endpoint}"><App /></OmnirecProvider>

const track = useTrack();
track("product_added_to_cart", { product: { id: product.id, quantity: 1 } });`;
    case "vue":
      return `npm install @omnirec/commerce-vue

import { OmnirecPlugin } from "@omnirec/commerce-vue";
app.use(OmnirecPlugin, { endpoint: "${endpoint}" });

<button v-track="['product_added_to_cart', { product: { id, quantity: 1 } }]">Add to cart</button>`;
    case "web":
      return `npm install @omnirec/commerce-web

import { createOmnirec } from "@omnirec/commerce-web";
import { dom } from "@omnirec/commerce-web/dom";
import { autocapture } from "@omnirec/commerce-web/autocapture";
const omnirec = createOmnirec({ endpoint: "${endpoint}", plugins: [dom(), autocapture()] });
omnirec.track("product_added_to_cart", { product: { id: "P100", quantity: 1 } });`;
    case "spring":
      return `<!-- pom.xml -->
<dependency>
  <groupId>io.omnirec</groupId>
  <artifactId>commerce-tracker-spring-boot</artifactId>
</dependency>

# application.yml
omnirec:
  tracker:
    endpoint: ${endpoint}

// in a controller or service
tracker.track(StandardEvents.PURCHASE_COMPLETED, Map.of("order", Map.of(
    "id", order.getId(), "total", order.getTotal(), "currency", "USD", "items", items)), customerId, order.getId());`;
    case "html":
      return `<!-- in <head>; copy dist/omnirec.min.js from @omnirec/commerce-web -->
<script>
  !function(w){var o=w.omnirec=w.omnirec||{q:[]};["track","identify","logout","flush"]
  .forEach(function(m){o[m]=o[m]||function(){o.q.push([m,[].slice.call(arguments)])}})}(window);
</script>
<script async src="/omnirec.min.js" data-endpoint="${endpoint}"></script>

<!-- no JavaScript needed per element -->
<button data-omnirec-event="product_added_to_cart" data-omnirec-product="P100" data-omnirec-quantity="1">Add</button>`;
  }
}

/**
 * `omnirec init`: writes a starter omnirec.plan.yaml and prints the setup for
 * the detected framework. Never overwrites without --force.
 */
export function runInit(options: InitOptions = {}): number {
  const log = options.log ?? ((line) => console.log(line));
  const cwd = resolve(options.cwd ?? process.cwd());
  const framework = options.framework ?? detectFramework(cwd);
  const endpoint = options.endpoint ?? "http://localhost:8124";
  const planPath = join(cwd, "omnirec.plan.yaml");

  if (existsSync(planPath) && !options.force) {
    log("omnirec init: omnirec.plan.yaml already exists; leaving it alone (use --force to replace it).");
  } else {
    writeFileSync(planPath, PLAN_TEMPLATE, "utf8");
    log(`omnirec init: wrote omnirec.plan.yaml`);
  }

  log("");
  log(`Detected: ${LABEL[framework]}`);
  log("");
  log(snippet(framework, endpoint));
  log("");
  log("Next steps:");
  log("  1. npx omnirec dev            start the local collector on :8124 (live list at http://localhost:8124/)");
  log("  2. paste the snippet above    and load a page: events appear in the terminal as they arrive");
  if (framework === "spring") {
    log("  3. npx omnirec generate       OmnirecEvents.java constants for your custom events");
  } else if (framework !== "html") {
    log("  3. npx omnirec generate       omnirec.d.ts, so track() type-checks your custom events");
  }
  log(`  ${framework === "html" ? 3 : 4}. npx omnirec validate       check the plan (in CI: --against origin/main)`);
  log("");
  log("For production, run the collector (docs/self-hosting.md) and point endpoint at it, ideally through a /omnirec path on your own domain.");
  return 0;
}
