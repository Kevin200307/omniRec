// SPDX-License-Identifier: Apache-2.0
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { detectFramework, parsePlan, PLAN_TEMPLATE, runInit } from "../src";

function project(files: Record<string, string>): string {
  const dir = mkdtempSync(join(tmpdir(), "omnirec-init-"));
  for (const [name, content] of Object.entries(files)) writeFileSync(join(dir, name), content);
  return dir;
}

const pkg = (deps: Record<string, string>) => JSON.stringify({ name: "shop", dependencies: deps });

function run(dir: string, force = false) {
  const lines: string[] = [];
  const code = runInit({ cwd: dir, force, log: (l) => lines.push(l) });
  return { code, out: lines.join("\n") };
}

describe("omnirec init", () => {
  it.each([
    ["next", { "package.json": pkg({ next: "15", react: "19" }) }, "OmnirecNextProvider"],
    ["react", { "package.json": pkg({ react: "19" }) }, "OmnirecProvider"],
    ["vue", { "package.json": pkg({ vue: "3" }) }, "OmnirecPlugin"],
    ["web", { "package.json": pkg({ express: "4" }) }, "createOmnirec"],
    ["spring", { "pom.xml": "<project><artifactId>spring-boot-starter-web</artifactId></project>" }, "commerce-tracker-spring-boot"],
    ["spring", { "build.gradle.kts": "plugins { id(\"org.springframework.boot\") }" }, "omnirec:\n  tracker:"],
    ["html", { "index.html": "<html></html>" }, "data-omnirec-event"],
  ])("detects %s and prints its setup", (framework, files, expected) => {
    const dir = project(files as Record<string, string>);
    expect(detectFramework(dir)).toBe(framework);
    const { code, out } = run(dir);
    expect(code).toBe(0);
    expect(out).toContain(expected);
    expect(out).toContain("http://localhost:8124");
    expect(out).toContain("omnirec dev");
    expect(readFileSync(join(dir, "omnirec.plan.yaml"), "utf8")).toBe(PLAN_TEMPLATE);
  });

  it("never overwrites an existing plan without --force", () => {
    const dir = project({ "package.json": pkg({}), "omnirec.plan.yaml": "events: {}\n" });
    const { out } = run(dir);
    expect(out).toContain("already exists");
    expect(readFileSync(join(dir, "omnirec.plan.yaml"), "utf8")).toBe("events: {}\n");

    run(dir, true);
    expect(readFileSync(join(dir, "omnirec.plan.yaml"), "utf8")).toBe(PLAN_TEMPLATE);
  });

  it("writes a plan the collector's rules accept", () => {
    const plan = parsePlan(PLAN_TEMPLATE, "omnirec.plan.yaml");
    expect(plan.events.map((e) => e.name)).toEqual(["size_guide_opened"]);
    expect(plan.events[0].required).toEqual(["product.id"]);
  });
});
