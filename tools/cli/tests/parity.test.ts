// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { findDrift, generateAll, loadCatalog } from "../src";
import { REPO_ROOT } from "./helpers";

/**
 * Parity with the hand-written sources that remain: the frozen v1 contract.
 * Both validators read their rules from the catalog (Java since Phase 2, the
 * browser since Phase 5), so there is nothing else left to compare.
 */
const catalog = loadCatalog(join(REPO_ROOT, "catalog"));
const read = (path: string) => readFileSync(join(REPO_ROOT, path), "utf8");


describe("catalog parity with the v1 sources", () => {
  it("still contains every v1 event under its v1 name (domains moved in Phase 9)", () => {
    // Frozen when the Java enum was removed in Phase 2. Removing or moving a v1
    // event is a breaking change for existing integrations and needs an alias.
    const v1: Record<string, string[]> = {
      session: ["session_started", "session_ended", "page_viewed", "home_page_viewed"],
      discovery: ["search_performed", "search_result_clicked", "product_list_viewed", "category_viewed", "product_viewed", "product_clicked"],
      product_interaction: ["product_wishlisted", "product_shared", "product_compared", "product_review_viewed", "product_review_submitted"],
      cart: ["cart_viewed", "product_added_to_cart", "product_removed_from_cart", "cart_quantity_updated", "cart_abandoned"],
      checkout: ["checkout_started", "shipping_information_added", "payment_information_added", "checkout_completed", "checkout_failed"],
      purchase: ["purchase_completed", "purchase_failed", "order_cancelled", "order_refunded"],
      recommendation: ["recommendation_impression", "recommendation_clicked", "recommendation_added_to_cart", "recommendation_purchased"],
      user: ["user_registered", "user_logged_in", "user_logged_out", "user_profile_updated"],
      identity: ["identify"],
    };
    const byName = new Map(catalog.events.flatMap((e) => [e.name, ...e.aliases].map((n) => [n, e] as const)));
    for (const names of Object.values(v1)) {
      for (const name of names) {
        expect(byName.get(name), name).toBeDefined();
        // v1 names stay canonical so stored history and filters keep working.
        expect(byName.get(name)!.name, name).toBe(name);
      }
    }
  });

  it("lists the same standard events as the v2 schema, and keeps every frozen v1 event", () => {
    const v2 = JSON.parse(read("schema/commerce-event.schema.json"));
    expect([...v2.properties.event["x-omnirec-standard-events"]].sort()).toEqual(catalog.events.map((e) => e.name).sort());
    const v1 = JSON.parse(read("schema/v1/commerce-event.schema.json"));
    const known = new Set(catalog.events.flatMap((e) => [e.name, ...e.aliases]));
    for (const name of v1.properties.eventType.enum) expect(known.has(name), name).toBe(true);
  });

  it("has a v2 home for every v1 commerce field", () => {
    // V1Compat (Java) and the browser SDK's upconversion use this mapping.
    const v1Fields: Record<string, string> = {
      productId: "product.id", productIds: "list.productIds", categoryId: "category.id",
      category: "category.name", quantity: "product.quantity", price: "product.price",
      currency: "product.currency", cartId: "cart.id", orderId: "order.id", searchQuery: "search.query",
      recommendationId: "recommendation.id", recommendationProvider: "recommendation.provider",
      listId: "list.id", total: "order.total", items: "order.items",
    };
    const v1 = JSON.parse(read("schema/v1/commerce-event.schema.json"));
    expect(Object.keys(v1.properties.commerce.properties).sort()).toEqual(Object.keys(v1Fields).sort());
    for (const target of Object.values(v1Fields)) {
      const [block, field] = target.split(".");
      expect(catalog.blocks.find((b) => b.name === block)?.fields[field], target).toBeDefined();
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

describe("catalog lint", () => {
  it("uses every block and every vocabulary", () => {
    const usedBlocks = new Set(catalog.events.flatMap((e) => e.blocks));
    for (const block of catalog.blocks) expect(usedBlocks.has(block.name), `block ${block.name} is unused`).toBe(true);
    const usedVocabs = new Set<string>();
    const collect = (spec: { vocabulary?: string; items?: unknown; fields?: Record<string, unknown> }) => {
      if (spec.vocabulary) usedVocabs.add(spec.vocabulary);
      if (spec.items) collect(spec.items as never);
      for (const child of Object.values(spec.fields ?? {})) collect(child as never);
    };
    for (const block of catalog.blocks) for (const field of Object.values(block.fields)) collect(field);
    for (const event of catalog.events) for (const field of event.fields) collect(field);
    for (const vocab of catalog.vocabularies) expect(usedVocabs.has(vocab.name), `vocabulary ${vocab.name} is unused`).toBe(true);
  });

  it("describes every event in a sentence", () => {
    for (const event of catalog.events) {
      expect(event.description.length, event.name).toBeGreaterThan(10);
      expect(event.description.trim(), event.name).toMatch(/.$/);
    }
  });

  it("has an example for every event that requires fields", () => {
    for (const event of catalog.events.filter((e) => e.required.length > 0)) {
      expect(event.example, event.name).toBeDefined();
    }
  });

  it("keeps the expected shape: twelve domains and the full lifecycle", () => {
    expect(catalog.domains).toHaveLength(12);
    expect(catalog.events.length).toBeGreaterThanOrEqual(180);
    for (const domain of catalog.domains) {
      expect(catalog.events.some((e) => e.domain === domain.id), domain.id).toBe(true);
    }
  });
});

describe("generated files in the repository", () => {
  it("are up to date with the catalog (run npm run catalog:generate if this fails)", () => {
    expect(findDrift(generateAll(catalog, REPO_ROOT), REPO_ROOT)).toEqual([]);
  });
});
