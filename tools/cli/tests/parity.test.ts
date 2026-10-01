// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { findDrift, generateAll, loadCatalog } from "../src";
import { REPO_ROOT } from "./helpers";

/**
 * Phase 1 parity: the seeded catalog must describe today's taxonomy exactly.
 * These tests read the hand-written v1 sources (Java enum, both validators,
 * schema) and compare them with the catalog. They keep passing through Phase 2,
 * when the enum and validators start reading the catalog instead.
 */
const catalog = loadCatalog(join(REPO_ROOT, "catalog"));
const read = (path: string) => readFileSync(join(REPO_ROOT, path), "utf8");

const RULE_PATHS: Record<string, string[]> = {
  ProductId: ["commerce.productId"],
  ProductIds: ["commerce.productIds"],
  CartId: ["commerce.cartId"],
  OrderId: ["commerce.orderId"],
  SearchQuery: ["commerce.searchQuery"],
  CategoryId: ["commerce.categoryId"],
  RecommendationId: ["commerce.recommendationId"],
  PositiveQuantity: ["commerce.quantity"],
  Currency: ["commerce.currency"],
  Total: ["commerce.total"],
  Items: ["commerce.items"],
  UserId: ["identity.userId"],
};

function pathsFor(ruleNames: string[]): string[] {
  return ruleNames
    .flatMap((name) => {
      const paths = RULE_PATHS[name];
      if (!paths) throw new Error(`unmapped validator rule require${name}`);
      return paths;
    })
    .sort();
}

const catalogRequired = Object.fromEntries(catalog.events.map((e) => [e.name, [...e.required].sort()]));

describe("catalog parity with the v1 sources", () => {
  it("has the same event names and domains as the Java EventType enum", () => {
    const source = read("backend/omnirec-commerce-core/src/main/java/io/omnirec/commerce/model/EventType.java");
    const entries = [...source.matchAll(/\("([a-z_]+)", EventCategory\.([A-Z_]+)\)/g)].map(([, name, category]) => [
      name,
      category.toLowerCase(),
    ]);
    expect(entries).toHaveLength(38);
    expect(Object.fromEntries(catalog.events.map((e) => [e.name, e.domain]))).toEqual(Object.fromEntries(entries));
  });

  it("has the same event names as the committed JSON Schema", () => {
    const schema = JSON.parse(read("schema/commerce-event.schema.json"));
    expect([...schema.properties.eventType.enum].sort()).toEqual(catalog.events.map((e) => e.name).sort());
  });

  it("requires exactly the fields the TypeScript validator requires", () => {
    const source = read("packages/commerce-web/src/validation/validator.ts");
    const block = source.match(/const RULES[^{]*\{([\s\S]*?)\n\};/);
    expect(block, "RULES table not found in validator.ts").toBeTruthy();
    const fromValidator: Record<string, string[]> = {};
    for (const [, event, rules] of block![1].matchAll(/^\s*([a-z_]+): \[([^\]]*)\]/gm)) {
      fromValidator[event] = pathsFor([...rules.matchAll(/require(\w+)/g)].map((m) => m[1]));
    }
    for (const event of catalog.events) {
      expect(catalogRequired[event.name], event.name).toEqual(fromValidator[event.name] ?? []);
    }
  });

  it("requires exactly the fields the Java validator requires", () => {
    const source = read(
      "backend/omnirec-commerce-core/src/main/java/io/omnirec/commerce/validation/EventValidator.java"
    );
    const fromValidator: Record<string, string[]> = {};
    for (const [, constant, rules] of source.matchAll(/map\.put\(EventType\.([A-Z_]+),\s*List\.of\(([\s\S]*?)\)\);/g)) {
      fromValidator[constant.toLowerCase()] = pathsFor([...rules.matchAll(/require(\w+)\(\)/g)].map((m) => m[1]));
    }
    expect(Object.keys(fromValidator).length).toBeGreaterThan(30);
    for (const event of catalog.events) {
      expect(catalogRequired[event.name], event.name).toEqual(fromValidator[event.name] ?? []);
    }
  });

  it("defines the commerce block with the same fields and types as the schema", () => {
    const schema = JSON.parse(read("schema/commerce-event.schema.json"));
    const schemaFields: Record<string, { type: string }> = schema.properties.commerce.properties;
    const block = catalog.blocks.find((b) => b.name === "commerce")!;
    expect(Object.keys(block.fields).sort()).toEqual(Object.keys(schemaFields).sort());
    for (const [name, field] of Object.entries(block.fields)) {
      expect(field.type, `commerce.${name}`).toBe(schemaFields[name].type);
    }
  });

  it("marks only identify as a control event", () => {
    expect(catalog.events.filter((e) => e.control).map((e) => e.name)).toEqual(["identify"]);
  });

  it("has an example for every event that requires fields", () => {
    for (const event of catalog.events.filter((e) => e.required.length > 0)) {
      expect(event.example, event.name).toBeDefined();
    }
  });
});

describe("generated files in the repository", () => {
  it("are up to date with the catalog (run npm run catalog:generate if this fails)", () => {
    expect(findDrift(generateAll(catalog, REPO_ROOT), REPO_ROOT)).toEqual([]);
  });
});
