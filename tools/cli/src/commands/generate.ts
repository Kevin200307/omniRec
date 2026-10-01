// SPDX-License-Identifier: Apache-2.0
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { CatalogLoadError, loadCatalog } from "../catalog/load";
import { findDrift, generateAll, writeGenerated, type Drift } from "../generate";

export interface GenerateOptions {
  /** Repository root. Found by walking up from cwd when omitted. */
  root?: string;
  /** Report drift and exit non-zero instead of writing. */
  check?: boolean;
  cwd?: string;
  log?: (line: string) => void;
}

/** Walks up from `start` to the folder that contains catalog/catalog.yaml. */
export function findRepoRoot(start: string): string | undefined {
  let current = resolve(start);
  for (;;) {
    if (existsSync(join(current, "catalog", "catalog.yaml"))) return current;
    const parent = dirname(current);
    if (parent === current) return undefined;
    current = parent;
  }
}

const LABEL: Record<Drift["kind"], string> = {
  changed: "out of date",
  missing: "missing",
  stale: "no longer generated",
};

/** Runs `omnirec generate`. Returns the process exit code. */
export function runGenerate(options: GenerateOptions = {}): number {
  const log = options.log ?? ((line) => console.log(line));
  const root = options.root ? resolve(options.root) : findRepoRoot(options.cwd ?? process.cwd());
  if (!root) {
    log("omnirec generate: could not find catalog/catalog.yaml in this folder or any parent. Use --root <dir>.");
    return 2;
  }

  let files;
  try {
    const catalog = loadCatalog(join(root, "catalog"));
    files = generateAll(catalog, root);
  } catch (error) {
    if (error instanceof CatalogLoadError) {
      log(error.message);
      return 1;
    }
    throw error;
  }

  if (options.check) {
    const drift = findDrift(files, root);
    if (drift.length === 0) {
      log(`omnirec generate --check: ${files.length} generated files are up to date.`);
      return 0;
    }
    log(`omnirec generate --check: ${drift.length} file(s) do not match the catalog:`);
    for (const item of drift) log(`  ${LABEL[item.kind].padEnd(20)} ${item.path}`);
    log("Run `npm run catalog:generate` and commit the result.");
    return 1;
  }

  const changed = writeGenerated(files, root);
  if (changed.length === 0) {
    log(`omnirec generate: ${files.length} generated files already up to date.`);
  } else {
    log(`omnirec generate: updated ${changed.length} of ${files.length} file(s):`);
    for (const item of changed) log(`  ${item.kind === "stale" ? "deleted" : "wrote"}  ${item.path}`);
  }
  return 0;
}
