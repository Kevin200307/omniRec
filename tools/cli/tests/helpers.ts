// SPDX-License-Identifier: Apache-2.0
import { cpSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export const TESTS_DIR = dirname(fileURLToPath(import.meta.url));
export const FIXTURE_ROOT = join(TESTS_DIR, "fixtures", "basic");
export const REPO_ROOT = join(TESTS_DIR, "..", "..", "..");

const created: string[] = [];

/** Copies the basic fixture into a fresh temp folder and returns its root. */
export function tempFixture(): string {
  const root = mkdtempSync(join(tmpdir(), "omnirec-cli-"));
  created.push(root);
  cpSync(FIXTURE_ROOT, root, { recursive: true });
  return root;
}

/** Writes (or overwrites) files relative to `root`. */
export function writeFiles(root: string, files: Record<string, string>): void {
  for (const [path, content] of Object.entries(files)) {
    const abs = join(root, path);
    mkdirSync(dirname(abs), { recursive: true });
    writeFileSync(abs, content, "utf8");
  }
}

export function removeTempFixtures(): void {
  while (created.length) rmSync(created.pop()!, { recursive: true, force: true });
}
