#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Adds an SPDX license header to every source file that lacks one.
//
//   node scripts/add-license-headers.mjs           add missing headers (idempotent)
//   node scripts/add-license-headers.mjs --check   report missing headers, exit 1 if any
//
// Files are taken from git (tracked plus untracked-but-not-ignored), so build
// output, node_modules, and anything else in .gitignore is never touched.
// A file already carrying an SPDX line in its first few lines is left alone,
// so the script is safe to run repeatedly. CI runs it with --check.
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";

const LICENSE_ID = "Apache-2.0";
const SPDX_MARKER = "SPDX-License-Identifier:";

/** Comment prefix per extension. Anything not listed is out of scope. */
const COMMENT_STYLE = {
  ".java": "//",
  ".ts": "//",
  ".tsx": "//",
  ".js": "//",
  ".jsx": "//",
  ".mjs": "//",
  ".cjs": "//",
  ".yml": "#",
  ".yaml": "#",
  ".sh": "#",
};

/**
 * Out of scope even when the extension matches: generated code, vendored or
 * build output, lock files, and test fixtures (data files under test resources).
 * JSON, Markdown and binaries are excluded by COMMENT_STYLE already.
 */
const SKIP_PATH = [
  /(^|\/)node_modules\//,
  /(^|\/)(dist|build|target|coverage|out)\//,
  /(^|\/)\.(next|turbo)\//,
  /(^|\/)(vendor|third[_-]party|generated)\//,
  /(^|\/)src\/test\/resources\//,
  /(^|\/)(__fixtures__|fixtures)\//,
  /\.d\.ts$/, // generated declarations, e.g. next-env.d.ts
  /(^|\/)[^/]*lock[^/]*\.ya?ml$/, // pnpm-lock.yaml and similar
];

/** Markers that identify a generated file in its first lines. */
const GENERATED_MARKER = /@generated|do not edit|auto-?generated/i;

function listFiles() {
  const run = (args) =>
    execFileSync("git", args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 })
      .split("\n")
      .map((f) => f.trim())
      .filter(Boolean);
  const files = new Set([...run(["ls-files"]), ...run(["ls-files", "--others", "--exclude-standard"])]);
  return [...files].sort();
}

function extensionOf(path) {
  const dot = path.lastIndexOf(".");
  return dot === -1 ? "" : path.slice(dot).toLowerCase();
}

function classify(path) {
  const style = COMMENT_STYLE[extensionOf(path)];
  if (!style) return { status: "out-of-scope" };
  if (SKIP_PATH.some((re) => re.test(path))) return { status: "skipped", reason: "excluded path" };

  let text;
  try {
    text = readFileSync(path, "utf8");
  } catch {
    return { status: "skipped", reason: "unreadable (deleted in the working tree?)" };
  }
  if (text.trim() === "") return { status: "skipped", reason: "empty file" };

  const head = text.split(/\r?\n/, 6).join("\n");
  if (head.includes(SPDX_MARKER)) {
    const id = head.slice(head.indexOf(SPDX_MARKER) + SPDX_MARKER.length).trim().split(/\s/)[0];
    return id === LICENSE_ID ? { status: "has-header" } : { status: "foreign-header", id };
  }
  if (GENERATED_MARKER.test(head)) return { status: "skipped", reason: "generated file" };
  return { status: "missing", style, text };
}

function withHeader(text, style) {
  const eol = text.includes("\r\n") ? "\r\n" : "\n";
  const bom = text.startsWith("﻿") ? "﻿" : "";
  const body = bom ? text.slice(1) : text;
  const header = `${style} ${SPDX_MARKER} ${LICENSE_ID}`;
  // A shebang must stay on the first line.
  if (body.startsWith("#!")) {
    const firstBreak = body.indexOf("\n");
    if (firstBreak === -1) return bom + body + eol + header + eol;
    return bom + body.slice(0, firstBreak + 1) + header + eol + body.slice(firstBreak + 1);
  }
  return bom + header + eol + body;
}

const checkOnly = process.argv.includes("--check");
const results = { "has-header": [], missing: [], "foreign-header": [], skipped: [] };

for (const path of listFiles()) {
  const result = classify(path);
  if (result.status === "out-of-scope") continue;
  results[result.status].push({ path, ...result });
  if (result.status === "missing" && !checkOnly) {
    writeFileSync(path, withHeader(result.text, result.style), "utf8");
  }
}

for (const { path, reason } of results.skipped) console.log(`skipped   ${path} (${reason})`);
for (const { path, id } of results["foreign-header"]) {
  console.log(`FOREIGN   ${path} (SPDX-License-Identifier: ${id}; left unchanged, review manually)`);
}

if (checkOnly) {
  for (const { path } of results.missing) console.log(`MISSING   ${path}`);
  console.log(
    `\n${results["has-header"].length} file(s) with the ${LICENSE_ID} header, ` +
      `${results.missing.length} missing, ${results.skipped.length} skipped, ` +
      `${results["foreign-header"].length} with a different license identifier.`,
  );
  if (results.missing.length > 0) {
    console.log("\nRun: node scripts/add-license-headers.mjs");
    process.exit(1);
  }
} else {
  for (const { path } of results.missing) console.log(`added     ${path}`);
  console.log(
    `\n${results.missing.length} header(s) added, ${results["has-header"].length} file(s) already had one, ` +
      `${results.skipped.length} skipped, ${results["foreign-header"].length} with a different license identifier.`,
  );
}
