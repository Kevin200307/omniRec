// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { runGenerate } from "../src";
import { removeTempFixtures, tempFixture, TESTS_DIR, writeFiles } from "./helpers";

afterEach(removeTempFixtures);

const TS_CATALOG = "packages/commerce-web/src/events/generated/catalog.ts";

function run(root: string, check: boolean) {
  const lines: string[] = [];
  const code = runGenerate({ root, check, log: (line) => lines.push(line) });
  return { code, output: lines.join("\n") };
}

describe("omnirec generate and generate --check", () => {
  it("reports missing files before the first generate, then passes after it", () => {
    const root = tempFixture();
    const before = run(root, true);
    expect(before.code).toBe(1);
    expect(before.output).toMatch(/missing\s+packages\/commerce-web\/src\/events\/generated\/catalog.ts/);

    const write = run(root, false);
    expect(write.code).toBe(0);
    expect(write.output).toMatch(/updated 10 of 10 file\(s\)/);

    expect(run(root, true)).toEqual({ code: 0, output: expect.stringMatching(/10 generated files are up to date/) });
    expect(run(root, false).output).toMatch(/already up to date/);
  });

  it("detects a hand-edited generated file, and generate restores it", () => {
    const root = tempFixture();
    run(root, false);
    const original = readFileSync(join(root, TS_CATALOG), "utf8");
    writeFileSync(join(root, TS_CATALOG), original + "\nexport const HACK = 1;\n");

    const check = run(root, true);
    expect(check.code).toBe(1);
    expect(check.output).toMatch(/out of date\s+packages\/commerce-web\/src\/events\/generated\/catalog.ts/);

    run(root, false);
    expect(readFileSync(join(root, TS_CATALOG), "utf8")).toBe(original);
  });

  it("detects a catalog change that was not regenerated", () => {
    const root = tempFixture();
    run(root, false);
    writeFiles(root, {
      "catalog/events/shop/product_compared.yaml":
        "# SPDX-License-Identifier: Apache-2.0\nname: product_compared\ndomain: shop\nversion: 1\nsources: [browser]\ndescription: Compared.\n",
    });
    const check = run(root, true);
    expect(check.code).toBe(1);
    expect(check.output).toMatch(/out of date\s+schema\/commerce-event.schema.json/);
  });

  it("treats unknown files in generated folders as stale and removes them", () => {
    const root = tempFixture();
    run(root, false);
    writeFiles(root, { "docs/events/old_domain.md": "# removed domain\n" });

    const check = run(root, true);
    expect(check.code).toBe(1);
    expect(check.output).toMatch(/no longer generated\s+docs\/events\/old_domain.md/);

    run(root, false);
    expect(existsSync(join(root, "docs/events/old_domain.md"))).toBe(false);
  });

  it("ignores CRLF line endings from a Windows checkout", () => {
    const root = tempFixture();
    run(root, false);
    const path = join(root, TS_CATALOG);
    writeFileSync(path, readFileSync(path, "utf8").replace(/\n/g, "\r\n"));
    expect(run(root, true).code).toBe(0);
  });

  it("fails with the catalog problems instead of writing anything", () => {
    const root = tempFixture();
    rmSync(join(root, "catalog/blocks/product.yaml"));
    const result = run(root, false);
    expect(result.code).toBe(1);
    expect(result.output).toMatch(/block "product" does not exist/);
    expect(existsSync(join(root, TS_CATALOG))).toBe(false);
  });

  it("exits 1 from the built command line on drift", () => {
    const cli = join(TESTS_DIR, "..", "dist", "cli.js");
    expect(existsSync(cli), "build @omnirec/cli before testing").toBe(true);
    const root = tempFixture();
    const result = spawnSync(process.execPath, [cli, "generate", "--check", "--root", root], { encoding: "utf8" });
    expect(result.status).toBe(1);
    expect(result.stdout).toMatch(/do not match the catalog/);

    const unknown = spawnSync(process.execPath, [cli, "frobnicate"], { encoding: "utf8" });
    expect(unknown.status).toBe(2);
  });
});
