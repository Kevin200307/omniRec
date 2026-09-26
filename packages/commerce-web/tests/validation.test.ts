// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from "vitest";
import { SCHEMA_VERSION, type CommerceData, type CommerceEvent, type EventType } from "../src/events/types";
import { EventValidator } from "../src/validation/validator";

const validator = new EventValidator();

function event(
  eventType: EventType,
  commerce: CommerceData = {},
  overrides: Partial<CommerceEvent> = {}
): CommerceEvent {
  return {
    eventId: "evt_1",
    eventType,
    schemaVersion: SCHEMA_VERSION,
    timestamp: "2026-01-01T00:00:00.000Z",
    identity: { anonymousId: "anon_A", userId: null, sessionId: "session_1" },
    context: { platform: "web" },
    commerce,
    properties: {},
    ...overrides,
  };
}

function errorFields(result: { errors: Array<{ field: string }> }): string[] {
  return result.errors.map((e) => e.field);
}

describe("EventValidator — universal rules", () => {
  it("accepts a well-formed event", () => {
    expect(validator.validate(event("product_viewed", { productId: "p1" })).valid).toBe(true);
  });

  it("rejects a missing eventId", () => {
    const result = validator.validate(event("page_viewed", {}, { eventId: "" }));

    expect(result.valid).toBe(false);
    expect(errorFields(result)).toContain("eventId");
  });

  it("rejects an unknown eventType", () => {
    const result = validator.validate(event("not_a_real_event" as EventType));

    expect(result.valid).toBe(false);
    expect(errorFields(result)).toContain("eventType");
  });

  it("rejects an invalid timestamp", () => {
    const result = validator.validate(event("page_viewed", {}, { timestamp: "last tuesday" }));

    expect(result.valid).toBe(false);
    expect(errorFields(result)).toContain("timestamp");
  });

  it("rejects a missing anonymousId", () => {
    const result = validator.validate(
      event("page_viewed", {}, { identity: { anonymousId: "", userId: null, sessionId: "s1" } })
    );

    expect(result.valid).toBe(false);
    expect(errorFields(result)).toContain("identity.anonymousId");
  });

  it("rejects a missing sessionId", () => {
    const result = validator.validate(
      event("page_viewed", {}, { identity: { anonymousId: "a1", userId: null, sessionId: "" } })
    );

    expect(result.valid).toBe(false);
    expect(errorFields(result)).toContain("identity.sessionId");
  });

  it("does not demand commerce fields for session events", () => {
    expect(validator.validate(event("session_started")).valid).toBe(true);
    expect(validator.validate(event("page_viewed")).valid).toBe(true);
    expect(validator.validate(event("home_page_viewed")).valid).toBe(true);
  });
});

describe("EventValidator — product rules", () => {
  it("requires productId on product_viewed", () => {
    const result = validator.validate(event("product_viewed"));

    expect(result.valid).toBe(false);
    expect(errorFields(result)).toContain("commerce.productId");
  });

  it("requires productId on product_clicked", () => {
    expect(validator.validate(event("product_clicked")).valid).toBe(false);
    expect(validator.validate(event("product_clicked", { productId: "p1" })).valid).toBe(true);
  });

  it("requires categoryId on category_viewed", () => {
    expect(validator.validate(event("category_viewed")).valid).toBe(false);
    expect(validator.validate(event("category_viewed", { categoryId: "c1" })).valid).toBe(true);
  });

  it("requires a non-empty productIds list on product_list_viewed", () => {
    expect(validator.validate(event("product_list_viewed", { productIds: [] })).valid).toBe(false);
    expect(validator.validate(event("product_list_viewed", { productIds: ["p1"] })).valid).toBe(true);
  });
});

describe("EventValidator — cart rules", () => {
  it("requires productId and quantity on product_added_to_cart", () => {
    const result = validator.validate(event("product_added_to_cart"));

    expect(errorFields(result)).toContain("commerce.productId");
    expect(errorFields(result)).toContain("commerce.quantity");
  });

  it("rejects a zero quantity", () => {
    const result = validator.validate(event("product_added_to_cart", { productId: "p1", quantity: 0 }));

    expect(result.valid).toBe(false);
    expect(errorFields(result)).toContain("commerce.quantity");
  });

  it("rejects a negative quantity", () => {
    const result = validator.validate(event("product_added_to_cart", { productId: "p1", quantity: -3 }));

    expect(result.valid).toBe(false);
    expect(result.errors[0].message).toMatch(/greater than 0/);
  });

  it("rejects a non-numeric quantity", () => {
    const result = validator.validate(
      event("product_added_to_cart", { productId: "p1", quantity: "two" as unknown as number })
    );

    expect(result.valid).toBe(false);
  });

  it("accepts a valid add-to-cart", () => {
    expect(validator.validate(event("product_added_to_cart", { productId: "p1", quantity: 2 })).valid).toBe(true);
  });

  it("requires cartId on cart_viewed and cart_abandoned", () => {
    expect(validator.validate(event("cart_viewed")).valid).toBe(false);
    expect(validator.validate(event("cart_abandoned")).valid).toBe(false);
    expect(validator.validate(event("cart_viewed", { cartId: "c1" })).valid).toBe(true);
  });
});

describe("EventValidator — purchase rules", () => {
  const validPurchase: CommerceData = {
    orderId: "o1",
    items: [{ productId: "p1", quantity: 1, price: 10 }],
    total: 10,
    currency: "USD",
  };

  it("accepts a complete purchase", () => {
    expect(validator.validate(event("purchase_completed", validPurchase)).valid).toBe(true);
  });

  it("requires orderId", () => {
    const result = validator.validate(event("purchase_completed", { ...validPurchase, orderId: undefined }));

    expect(errorFields(result)).toContain("commerce.orderId");
  });

  it("requires a non-empty items list", () => {
    const result = validator.validate(event("purchase_completed", { ...validPurchase, items: [] }));

    expect(errorFields(result)).toContain("commerce.items");
  });

  it("requires currency", () => {
    const result = validator.validate(event("purchase_completed", { ...validPurchase, currency: undefined }));

    expect(errorFields(result)).toContain("commerce.currency");
  });

  it("rejects a currency that is not ISO 4217", () => {
    const result = validator.validate(event("purchase_completed", { ...validPurchase, currency: "dollars" }));

    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.message.includes("ISO 4217"))).toBe(true);
  });

  it("requires total", () => {
    const result = validator.validate(event("purchase_completed", { ...validPurchase, total: undefined }));

    expect(errorFields(result)).toContain("commerce.total");
  });

  it("rejects a negative total", () => {
    const result = validator.validate(event("purchase_completed", { ...validPurchase, total: -5 }));

    expect(errorFields(result)).toContain("commerce.total");
  });

  it("rejects an item without a productId", () => {
    const result = validator.validate(
      event("purchase_completed", { ...validPurchase, items: [{ productId: "" }] })
    );

    expect(errorFields(result)).toContain("commerce.items[0].productId");
  });
});

describe("EventValidator — search and recommendation rules", () => {
  it("requires a query on search_performed", () => {
    expect(validator.validate(event("search_performed")).valid).toBe(false);
    expect(validator.validate(event("search_performed", { searchQuery: "laptop" })).valid).toBe(true);
  });

  it("requires query and productId on search_result_clicked", () => {
    const result = validator.validate(event("search_result_clicked", { searchQuery: "laptop" }));

    expect(errorFields(result)).toContain("commerce.productId");
  });

  it("requires recommendationId and productIds on an impression", () => {
    const result = validator.validate(event("recommendation_impression"));

    expect(errorFields(result)).toContain("commerce.recommendationId");
    expect(errorFields(result)).toContain("commerce.productIds");
  });

  it("requires recommendationId and productId on a click", () => {
    expect(
      validator.validate(event("recommendation_clicked", { recommendationId: "r1", productId: "p1" })).valid
    ).toBe(true);
    expect(validator.validate(event("recommendation_clicked", { productId: "p1" })).valid).toBe(false);
  });
});

describe("EventValidator — user rules", () => {
  it("requires a userId on identity-bearing events", () => {
    for (const type of ["user_registered", "user_logged_in", "user_profile_updated", "identify"] as const) {
      expect(validator.validate(event(type)).valid, `${type} without userId`).toBe(false);
    }
  });

  it("accepts them once a userId is present", () => {
    const identified = { anonymousId: "a1", userId: "customer_123", sessionId: "s1" };

    expect(validator.validate(event("user_logged_in", {}, { identity: identified })).valid).toBe(true);
    expect(validator.validate(event("identify", {}, { identity: identified })).valid).toBe(true);
  });

  it("does not require a userId to log out", () => {
    expect(validator.validate(event("user_logged_out")).valid).toBe(true);
  });
});
