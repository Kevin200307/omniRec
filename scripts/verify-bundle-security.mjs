#!/usr/bin/env node
/**
 * Fails the build if a provider credential or provider SDK could reach the browser.
 *
 * This enforces the architecture's central security claim in CI rather than in a
 * paragraph of documentation: the frontend holds a publishable key and an
 * endpoint, and nothing else. Provider credentials live server-side, and the
 * browser has no code path to a provider at all.
 *
 * Run after `turbo run build`:
 *   node scripts/verify-bundle-security.mjs
 */

import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { join, relative } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");

/** Everything a browser bundle is allowed to be built from. */
const SCAN_TARGETS = [
  "packages/commerce-web/dist",
  "packages/commerce-react/dist",
  "examples/nextjs-demo-store/.next/static",
];

const SCANNABLE = /\.(js|mjs|cjs|json|map|txt|html)$/;

/**
 * Each rule is deliberately narrow. A bare substring search for "AKIA" matches
 * the SDK's own guard regex — the code whose entire job is to *reject* an AWS
 * key — and a bare search for "googleapis.com" matches Next.js's font
 * preconnect. Both would be false positives that train everyone to ignore this
 * script, which is worse than not having it.
 */
const RULES = [
  {
    name: "AWS access key id",
    // A real key is 'AKIA' + exactly 16 uppercase alphanumerics as a literal.
    // The guard regex in config.ts reads `AKIA[0-9A-Z]{16}` — bracket after
    // AKIA — so it cannot match this.
    pattern: /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/g,
  },
  {
    name: "AWS secret access key",
    pattern: /aws_?secret_?access_?key\s*[:=]\s*["'][^"']{20,}["']/gi,
  },
  {
    name: "private key block",
    pattern: /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[A-Za-z0-9+/=\s]{40,}/g,
  },
  {
    name: "Google service account key",
    pattern: /"type"\s*:\s*"service_account"/g,
  },
  {
    name: "secret (non-publishable) API key",
    pattern: /\bsk_(?:live|test)_[A-Za-z0-9]{8,}/g,
  },
  {
    name: "Azure connection string",
    pattern: /(?:AccountKey|SharedAccessKey)=[A-Za-z0-9+/=]{20,}/g,
  },
  {
    name: "AWS provider SDK",
    // A provider SDK in a browser bundle means a direct browser -> provider
    // call path exists, which the architecture forbids regardless of credentials.
    pattern: /@aws-sdk\/client-personalize|personalizeevents|software\.amazon\.awssdk/g,
  },
  {
    name: "Google Cloud provider SDK",
    pattern: /@google-cloud\/retail|google\.cloud\.retail/g,
  },
];

function walk(dir) {
  const files = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const stats = statSync(full);
    if (stats.isDirectory()) {
      files.push(...walk(full));
    } else if (SCANNABLE.test(entry)) {
      files.push(full);
    }
  }
  return files;
}

let scanned = 0;
const violations = [];
const skipped = [];

for (const target of SCAN_TARGETS) {
  const dir = join(ROOT, target);
  if (!existsSync(dir)) {
    skipped.push(target);
    continue;
  }
  for (const file of walk(dir)) {
    scanned++;
    const contents = readFileSync(file, "utf8");
    for (const rule of RULES) {
      const matches = contents.match(rule.pattern);
      if (matches) {
        violations.push({
          file: relative(ROOT, file),
          rule: rule.name,
          // Truncated: this output can end up in CI logs, which are often public.
          sample: matches[0].slice(0, 24) + "…",
        });
      }
    }
  }
}

if (skipped.length > 0) {
  console.warn(`⚠ not built, so not scanned: ${skipped.join(", ")}`);
}

if (violations.length > 0) {
  console.error(`\n✖ provider credentials or SDKs found in ${violations.length} place(s):\n`);
  for (const violation of violations) {
    console.error(`  ${violation.file}\n    ${violation.rule}: ${violation.sample}`);
  }
  console.error(
    "\nThe browser must never hold a provider credential or reach a provider directly.\n" +
      "Move this to the Event API, which holds credentials server-side. See docs/security.md.\n"
  );
  process.exit(1);
}

if (scanned === 0) {
  console.error("✖ nothing was scanned — run `npx turbo run build` first");
  process.exit(1);
}

console.log(`✓ ${scanned} bundle file(s) scanned — no provider credentials or SDKs reach the browser`);
