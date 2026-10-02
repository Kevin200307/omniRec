// SPDX-License-Identifier: Apache-2.0
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { loadPlanFile, PlanError } from "../project/plan";
import { javaConstants, pluginTypes } from "../project/typegen";

export interface ProjectGenerateOptions {
  /** The plan file. Default: omnirec.plan.yaml in cwd. */
  plan?: string;
  cwd?: string;
  check?: boolean;
  /** Where to write the TypeScript declarations. Default: omnirec.d.ts next to the plan, in a JS/TS project. */
  tsOut?: string;
  /** Java package for OmnirecEvents. Default: omnirec. */
  javaPackage?: string;
  /** Java source root. Default: src/main/java, in a Maven or Gradle project. */
  javaRoot?: string;
  log?: (line: string) => void;
}

/**
 * `omnirec generate` in a store's own project: types for the plan's custom
 * events. TypeScript declarations when there is a package.json, Java
 * constants when there is a pom.xml or build.gradle.
 */
export function runProjectGenerate(options: ProjectGenerateOptions = {}): number {
  const log = options.log ?? ((line) => console.log(line));
  const cwd = resolve(options.cwd ?? process.cwd());
  const planPath = resolve(cwd, options.plan ?? "omnirec.plan.yaml");
  if (!existsSync(planPath)) {
    log(`omnirec generate: no ${relative(cwd, planPath) || planPath} here. Run \`omnirec init\` first, or pass --plan <file>.`);
    return 2;
  }
  let plan;
  try {
    plan = loadPlanFile(planPath);
  } catch (error) {
    if (error instanceof PlanError) {
      log("omnirec generate: the plan has problems:");
      for (const problem of error.problems) log(`  ${problem}`);
      return 1;
    }
    throw error;
  }

  const projectDir = dirname(planPath);
  const files: Array<{ path: string; content: string }> = [];
  const isJs = options.tsOut !== undefined || existsSync(join(projectDir, "package.json"));
  const isJava = options.javaRoot !== undefined || options.javaPackage !== undefined
    || existsSync(join(projectDir, "pom.xml")) || existsSync(join(projectDir, "build.gradle"))
    || existsSync(join(projectDir, "build.gradle.kts"));
  if (isJs) files.push({ path: resolve(projectDir, options.tsOut ?? "omnirec.d.ts"), content: pluginTypes(plan) });
  if (isJava) {
    const pkg = options.javaPackage ?? "omnirec";
    const root = resolve(projectDir, options.javaRoot ?? "src/main/java");
    files.push({ path: join(root, ...pkg.split("."), "OmnirecEvents.java"), content: javaConstants(plan, pkg) });
  }
  if (files.length === 0) {
    log("omnirec generate: found no package.json, pom.xml or build.gradle next to the plan; nothing to generate.");
    return 2;
  }

  const stale = files.filter((f) => !existsSync(f.path) || readFileSync(f.path, "utf8").replace(/\r\n/g, "\n") !== f.content);
  if (options.check) {
    if (stale.length === 0) {
      log(`omnirec generate --check: ${files.length} file(s) match ${relative(cwd, planPath)}.`);
      return 0;
    }
    log("omnirec generate --check: out of date:");
    for (const file of stale) log(`  ${relative(cwd, file.path)}`);
    log("Run `omnirec generate`.");
    return 1;
  }
  for (const file of stale) {
    mkdirSync(dirname(file.path), { recursive: true });
    writeFileSync(file.path, file.content, "utf8");
  }
  const custom = plan.events.length;
  log(`omnirec generate: ${custom} custom event(s); ${stale.length ? "wrote " + stale.map((f) => relative(cwd, f.path)).join(", ") : "already up to date"}.`);
  return 0;
}
