// SPDX-License-Identifier: Apache-2.0
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { generateAll, loadCatalog } from "../src";
import { FIXTURE_ROOT, TESTS_DIR } from "./helpers";

/**
 * Golden tests: the fixture catalog must produce exactly the files under
 * tests/golden/. After an intended generator change, regenerate them with
 *
 *   UPDATE_GOLDEN=1 npx vitest run tests/generate.test.ts
 *
 * and review the diff like any other code change.
 */
const GOLDEN_DIR = join(TESTS_DIR, "golden", "basic");
const files = generateAll(loadCatalog(join(FIXTURE_ROOT, "catalog")), FIXTURE_ROOT);

describe("generateAll on the fixture catalog", () => {
  it("produces the expected set of files", () => {
    expect(files.map((f) => f.path)).toEqual([
      "backend/omnirec-commerce-core/src/main/java/io/omnirec/commerce/catalog/generated/StandardEvents.java",
      "backend/omnirec-commerce-core/src/main/resources/omnirec/catalog.json",
      "docs/events/account.md",
      "docs/events/README.md",
      "docs/events/shop.md",
      "packages/commerce-web/src/events/generated/catalog.json",
      "packages/commerce-web/src/events/generated/catalog.ts",
      "schema/commerce-event.schema.json",
    ]);
  });

  for (const file of files) {
    it(`matches golden ${file.path}`, () => {
      const golden = join(GOLDEN_DIR, file.path);
      if (process.env.UPDATE_GOLDEN) {
        mkdirSync(dirname(golden), { recursive: true });
        writeFileSync(golden, file.content, "utf8");
      }
      expect(existsSync(golden), `missing golden file ${golden}`).toBe(true);
      expect(file.content).toBe(readFileSync(golden, "utf8").replace(/\r\n/g, "\n"));
    });
  }

  it("is deterministic", () => {
    const again = generateAll(loadCatalog(join(FIXTURE_ROOT, "catalog")), FIXTURE_ROOT);
    expect(again).toEqual(files);
  });

  it("writes byte-identical runtime catalogs for the browser and the JVM", () => {
    const browser = files.find((f) => f.path.endsWith("generated/catalog.json"))!;
    const jvm = files.find((f) => f.path.endsWith("resources/omnirec/catalog.json"))!;
    expect(browser.content).toBe(jvm.content);
  });

  it("fills the schema enum from the catalog and leaves the rest of the template alone", () => {
    const schema = JSON.parse(files.find((f) => f.path === "schema/commerce-event.schema.json")!.content);
    expect(schema.properties.eventType.enum).toEqual(["product_shared", "product_viewed", "account_linked"]);
    expect(schema.properties.eventType.type).toBe("string");
  });

  it("gives every generated source file a license header and a do-not-edit notice", () => {
    for (const file of files.filter((f) => /\.(ts|java)$/.test(f.path))) {
      expect(file.content.startsWith("// SPDX-License-Identifier: Apache-2.0\n// GENERATED")).toBe(true);
    }
    for (const file of files.filter((f) => f.path.endsWith(".md"))) {
      expect(file.content.startsWith("<!-- GENERATED")).toBe(true);
    }
  });
});
