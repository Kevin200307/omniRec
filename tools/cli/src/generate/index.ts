// SPDX-License-Identifier: Apache-2.0
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import type { Catalog } from "../catalog/model";
import { generateDocs } from "./docs";
import { generateJava } from "./java";
import { generateJsonSchema } from "./jsonschema";
import { runtimeCatalogJson } from "./runtime-json";
import { OWNED, PATHS, type GeneratedFile, type OutputLayout } from "./types";
import { generateTypeScript } from "./typescript";

/** Every file the catalog produces, sorted by path. */
export function generateAll(catalog: Catalog, repoRoot: string): GeneratedFile[] {
  const files = [
    generateTypeScript(catalog),
    { path: PATHS.tsCatalogJson, content: runtimeCatalogJson(catalog) },
    // The CLI ships the standard catalog so `omnirec dev` and `validate` work in any project.
    { path: PATHS.cliCatalogJson, content: runtimeCatalogJson(catalog) },
    ...generateJava(catalog),
    generateJsonSchema(catalog, repoRoot),
    ...generateDocs(catalog),
  ];
  return files.sort((a, b) => a.path.localeCompare(b.path));
}

export type DriftKind = "changed" | "missing" | "stale";

export interface Drift {
  path: string;
  kind: DriftKind;
}

/** Git may check text files out with CRLF on Windows; content equality ignores that. */
const normalise = (text: string) => text.replace(/\r\n/g, "\n");

function listFiles(root: string, dir: string): string[] {
  const abs = join(root, dir);
  if (!existsSync(abs)) return [];
  const out: string[] = [];
  for (const entry of readdirSync(abs)) {
    const rel = `${dir}/${entry}`;
    if (statSync(join(root, rel)).isDirectory()) out.push(...listFiles(root, rel));
    else out.push(rel);
  }
  return out;
}

/** Compares generated files with what is on disk. Writes nothing. */
export function findDrift(files: GeneratedFile[], repoRoot: string, layout: OutputLayout = OWNED): Drift[] {
  const drift: Drift[] = [];
  const expected = new Set(files.map((f) => f.path));
  for (const file of files) {
    const abs = join(repoRoot, file.path);
    if (!existsSync(abs)) drift.push({ path: file.path, kind: "missing" });
    else if (normalise(readFileSync(abs, "utf8")) !== normalise(file.content)) drift.push({ path: file.path, kind: "changed" });
  }
  for (const dir of layout.ownedDirectories) {
    for (const path of listFiles(repoRoot, dir)) {
      if (!expected.has(path)) drift.push({ path, kind: "stale" });
    }
  }
  return drift.sort((a, b) => a.path.localeCompare(b.path));
}

/**
 * Brings the disk in line with the generated files: writes changed or missing
 * files and deletes stale ones inside owned directories. Returns what changed.
 */
export function writeGenerated(files: GeneratedFile[], repoRoot: string, layout: OutputLayout = OWNED): Drift[] {
  const drift = findDrift(files, repoRoot, layout);
  const byPath = new Map(files.map((f) => [f.path, f]));
  for (const item of drift) {
    const abs = join(repoRoot, item.path);
    if (item.kind === "stale") {
      rmSync(abs);
    } else {
      mkdirSync(dirname(abs), { recursive: true });
      writeFileSync(abs, byPath.get(item.path)!.content, "utf8");
    }
  }
  return drift;
}
