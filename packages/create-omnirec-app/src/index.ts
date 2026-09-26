#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
import { mkdirSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import {
  frontendPackageJson,
  envExample,
  providersTsx,
  layoutTsx,
  pageTsx,
  backendPomXml,
  backendApplicationJava,
  backendApplicationYml,
  readmeMd,
  type ScaffoldOptions,
  type RecommendationChoice,
  type CacheChoice,
} from "./templates.js";

/**
 * Eliminates the "which starter do I even need" decision the design doc
 * calls out as the biggest adoption blocker — pick providers as flags, get
 * a working Next.js + Spring Boot project with only those dependencies
 * wired in, everything else left at enabled:false.
 */
function parseArgs(argv: string[]): ScaffoldOptions {
  const [projectName, ...flags] = argv;
  if (!projectName || projectName.startsWith("--")) {
    console.error("Usage: npx create-omnirec-app <project-name> [--recommendation=aws-personalize|google-rec-ai] [--cache=redis]");
    process.exit(1);
  }

  const get = (flag: string): string | undefined =>
    flags.find((f) => f.startsWith(`--${flag}=`))?.split("=")[1];

  return {
    projectName,
    recommendation: (get("recommendation") as RecommendationChoice) ?? "none",
    cache: (get("cache") as CacheChoice) ?? "none",
  };
}

function write(path: string, content: string): void {
  writeFileSync(path, content);
}

function scaffold(opts: ScaffoldOptions): void {
  const root = join(process.cwd(), opts.projectName);
  if (existsSync(root)) {
    console.error(`Directory "${opts.projectName}" already exists — aborting.`);
    process.exit(1);
  }

  mkdirSync(root, { recursive: true });
  mkdirSync(join(root, "app"), { recursive: true });
  mkdirSync(join(root, "backend", "src", "main", "java", "com", "example", "backend"), { recursive: true });
  mkdirSync(join(root, "backend", "src", "main", "resources"), { recursive: true });

  write(join(root, "package.json"), frontendPackageJson(opts));
  write(join(root, ".env.local.example"), envExample);
  write(join(root, "app", "providers.tsx"), providersTsx);
  write(join(root, "app", "layout.tsx"), layoutTsx);
  write(join(root, "app", "page.tsx"), pageTsx);

  write(join(root, "backend", "pom.xml"), backendPomXml(opts));
  write(join(root, "backend", "src", "main", "java", "com", "example", "backend", "Application.java"), backendApplicationJava);
  write(join(root, "backend", "src", "main", "resources", "application.yml"), backendApplicationYml(opts));

  write(join(root, "README.md"), readmeMd(opts));
  write(join(root, ".gitignore"), "node_modules/\n.next/\ntarget/\n.env.local\n");

  console.log(`Created ${opts.projectName}/`);
  console.log(`  recommendation: ${opts.recommendation}`);
  console.log(`  cache:          ${opts.cache}`);
  console.log();
  console.log(`Next steps:`);
  console.log(`  cd ${opts.projectName}`);
  console.log(`  cp .env.local.example .env.local && npm install && npm run dev`);
  console.log(`  cd backend && mvn spring-boot:run`);
}

scaffold(parseArgs(process.argv.slice(2)));
