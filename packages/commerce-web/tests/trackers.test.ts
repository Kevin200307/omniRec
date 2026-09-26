// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CommerceClient } from "../src/core/client";
import type { CommerceEvent, EventType } from "../src/events/types";

/**
 * Drives a real CommerceClient against a fake fetch, so these tests exercise
 * the whole path a merchant call actually takes: tracker -> identity ->
 * validation -> batching -> transport.
 */
function harness(overrides: Record<string, unknown> = {}) {
  const sent: CommerceEvent[] = [];
  const fetchImpl = vi.fn(async (_url: string, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body));
    sent.push(...body.events);
    return new Response(null, { status: 202 });
  }) as unknown as typeof fetch;

  const errors: Error[] = [];
  const client = new CommerceClient({
    apiKey: "pk_test_123",
    endpoint: "https://events.example.com",
    tenantId: "demo-store",
    maxBatchSize: 1000,
    autoTrackSessions: false,
    autoTrackDwellTime: false,
    fetchImpl,
    onError: (error) => errors.push(error),
    ...overrides,
  });

  return {
    client,
    errors,
    sent,
    async flush() {
      await client.flush();
      return sent;
    },
  };
}

function typesOf(events: CommerceEvent[]): EventType[] {
  return events.map((e) => e.eventType);
}

beforeEach(() => {
  localStorage.clear();
  document.cookie = "omnirec_anonymous_id=; path=/; max-age=0";
});

describe("every tracker produces a canonical event", () => {
  it("session_started / session_ended", async () => {
    const h = harness();
    h.client.session.started();
    h.client.session.ended();

    expect(typesOf(await h.flush())).toEqual(["session_started", "session_ended"]);
  });

  it("page_viewed and home_page_viewed", async () => {
    const h = harness();
    h.client.page.viewed();
    h.client.home.viewed();

    expect(typesOf(await h.flush())).toEqual(["page_viewed", "home_page_viewed"]);
  });

  it("search_performed carries the query in commerce.searchQuery", async () => {
    const h = harness();
    h.client.search.performed({ query: "gaming laptop", resultCount: 24 });

    const [event] = await h.flush();
    expect(event.eventType).toBe("search_performed");
    expect(event.commerce.searchQuery).toBe("gaming laptop");
    expect(event.properties.resultCount).toBe(24);
  });

  it("search_result_clicked keeps position in properties", async () => {
    const h = harness();
    h.client.search.resultClicked({ query: "gaming laptop", productId: "p123", position: 3 });

    const [event] = await h.flush();
    expect(event.commerce.productId).toBe("p123");
    expect(event.properties.position).toBe(3);
  });

  it("product_list_viewed", async () => {
    const h = harness();
    h.client.productList.viewed({ listId: "gaming-laptops", productIds: ["p1", "p2", "p3"] });

    const [event] = await h.flush();
    expect(event.commerce.productIds).toEqual(["p1", "p2", "p3"]);
    expect(event.commerce.listId).toBe("gaming-laptops");
  });

  it("category_viewed", async () => {
    const h = harness();
    h.client.category.viewed({ categoryId: "gaming-laptops" });

    const [event] = await h.flush();
    expect(event.commerce.categoryId).toBe("gaming-laptops");
  });

  it("product_viewed carries price and currency", async () => {
    const h = harness();
    h.client.product.viewed({ productId: "p123", categoryId: "laptops", price: 1500, currency: "USD" });

    const [event] = await h.flush();
    expect(event.commerce).toMatchObject({ productId: "p123", price: 1500, currency: "USD" });
  });

  it("product_clicked, wishlisted, shared, compared, review viewed/submitted", async () => {
    const h = harness();
    h.client.product.clicked({ productId: "p123" });
    h.client.product.wishlisted({ productId: "p123" });
    h.client.product.shared({ productId: "p123", method: "whatsapp" });
    h.client.product.compared({ productId: "p123" });
    h.client.product.reviewViewed({ productId: "p123" });
    h.client.product.reviewSubmitted({ productId: "p123", rating: 5 });

    const events = await h.flush();
    expect(typesOf(events)).toEqual([
      "product_clicked",
      "product_wishlisted",
      "product_shared",
      "product_compared",
      "product_review_viewed",
      "product_review_submitted",
    ]);
    expect(events[2].properties.method).toBe("whatsapp");
  });

  it("cart events", async () => {
    const h = harness();
    h.client.cart.viewed({ cartId: "cart_123" });
    h.client.cart.productAdded({ cartId: "cart_123", productId: "p123", quantity: 2, price: 1200, currency: "USD" });
    h.client.cart.productRemoved({ cartId: "cart_123", productId: "p123" });
    h.client.cart.quantityUpdated({ cartId: "cart_123", productId: "p123", previousQuantity: 1, newQuantity: 3 });

    const events = await h.flush();
    expect(typesOf(events)).toEqual([
      "cart_viewed",
      "product_added_to_cart",
      "product_removed_from_cart",
      "cart_quantity_updated",
    ]);
    expect(events[1].commerce.quantity).toBe(2);
    // newQuantity becomes the canonical quantity, previousQuantity stays a property.
    expect(events[3].commerce.quantity).toBe(3);
    expect(events[3].properties.previousQuantity).toBe(1);
  });

  it("defaults add-to-cart quantity to 1", async () => {
    const h = harness();
    h.client.cart.productAdded({ cartId: "cart_123", productId: "p123" });

    const [event] = await h.flush();
    expect(event.commerce.quantity).toBe(1);
  });

  it("checkout events", async () => {
    const h = harness();
    h.client.checkout.started({ cartId: "cart_123" });
    h.client.checkout.shippingInformationAdded({ cartId: "cart_123" });
    h.client.checkout.paymentInformationAdded({ cartId: "cart_123", paymentMethod: "card" });
    h.client.checkout.completed({ cartId: "cart_123", orderId: "order_1" });
    h.client.checkout.failed({ cartId: "cart_123", reason: "declined" });

    const events = await h.flush();
    expect(typesOf(events)).toEqual([
      "checkout_started",
      "shipping_information_added",
      "payment_information_added",
      "checkout_completed",
      "checkout_failed",
    ]);
    expect(events[2].properties.paymentMethod).toBe("card");
  });

  it("purchase events", async () => {
    const h = harness();
    const items = [{ productId: "p1", quantity: 1, price: 10 }];
    h.client.purchase.completed({ orderId: "o1", items, total: 10, currency: "USD" });
    h.client.purchase.failed({ orderId: "o2", reason: "declined" });
    h.client.purchase.orderCancelled({ orderId: "o1" });
    h.client.purchase.orderRefunded({ orderId: "o1" });

    expect(typesOf(await h.flush())).toEqual([
      "purchase_completed",
      "purchase_failed",
      "order_cancelled",
      "order_refunded",
    ]);
  });

  it("recommendation events", async () => {
    const h = harness();
    h.client.recommendation.impression({ recommendationId: "rec_123", productIds: ["p1", "p2"], source: "homepage" });
    h.client.recommendation.clicked({ recommendationId: "rec_123", productId: "p2" });
    h.client.recommendation.addedToCart({ recommendationId: "rec_123", productId: "p2" });
    h.client.recommendation.purchased({ recommendationId: "rec_123", productId: "p2" });

    const events = await h.flush();
    expect(typesOf(events)).toEqual([
      "recommendation_impression",
      "recommendation_clicked",
      "recommendation_added_to_cart",
      "recommendation_purchased",
    ]);
    expect(events[0].properties.source).toBe("homepage");
  });

  it("user events", async () => {
    const h = harness();
    h.client.user.registered({ userId: "customer_123" });
    h.client.user.loggedOut();
    h.client.user.loggedIn({ userId: "customer_123" });
    h.client.user.profileUpdated({ userId: "customer_123" });

    const types = typesOf(await h.flush());
    expect(types).toContain("user_registered");
    expect(types).toContain("user_logged_out");
    expect(types).toContain("user_logged_in");
    expect(types).toContain("user_profile_updated");
  });
});

describe("automatic context", () => {
  it("stamps identity, timestamp, schemaVersion, and a unique eventId without being asked", async () => {
    const h = harness();
    h.client.product.viewed({ productId: "p1" });
    h.client.product.viewed({ productId: "p2" });

    const events = await h.flush();
    for (const event of events) {
      expect(event.eventId).toBeTruthy();
      expect(event.schemaVersion).toBe("1.0");
      expect(Number.isFinite(Date.parse(event.timestamp))).toBe(true);
      expect(event.identity.anonymousId).toBeTruthy();
      expect(event.identity.sessionId).toBeTruthy();
      expect(event.context.platform).toBe("web");
    }
    expect(events[0].eventId).not.toBe(events[1].eventId);
  });

  it("puts every event in the same session", async () => {
    const h = harness();
    h.client.page.viewed();
    h.client.product.viewed({ productId: "p1" });

    const events = await h.flush();
    expect(events[0].identity.sessionId).toBe(events[1].identity.sessionId);
  });

  it("never lets the client supply ip or country", async () => {
    const h = harness();
    h.client.page.viewed();

    const [event] = await h.flush();
    expect(event.context.ip).toBeUndefined();
    expect(event.context.country).toBeUndefined();
  });
});

describe("identify and logout through the client", () => {
  it("emits an identify event that links anonymous to user", async () => {
    const h = harness();
    h.client.product.viewed({ productId: "p1" });
    const anonymousBefore = h.client.getIdentity().anonymousId;

    h.client.identify({ userId: "customer_123" });

    const events = await h.flush();
    const identify = events.find((e) => e.eventType === "identify");
    expect(identify).toBeDefined();
    expect(identify!.identity.userId).toBe("customer_123");
    expect(identify!.identity.anonymousId).toBe(anonymousBefore);
  });

  it("does not re-emit identify for the same user", async () => {
    const h = harness();
    h.client.identify({ userId: "customer_123" });
    h.client.identify({ userId: "customer_123" });

    const events = await h.flush();
    expect(events.filter((e) => e.eventType === "identify")).toHaveLength(1);
  });

  it("keeps the anonymousId after logout", async () => {
    const h = harness();
    const anonymousId = h.client.getIdentity().anonymousId;
    h.client.identify({ userId: "customer_123" });

    h.client.logout();

    expect(h.client.getIdentity().userId).toBeNull();
    expect(h.client.getIdentity().anonymousId).toBe(anonymousId);
  });

  it("attributes user_logged_out to the user who logged out", async () => {
    const h = harness();
    h.client.identify({ userId: "customer_123" });

    h.client.user.loggedOut();

    const events = await h.flush();
    const loggedOut = events.find((e) => e.eventType === "user_logged_out");
    expect(loggedOut!.identity.userId).toBe("customer_123");
  });

  it("carries the userId on every event after login", async () => {
    const h = harness();
    h.client.identify({ userId: "customer_123" });
    h.client.product.viewed({ productId: "p1" });

    const events = await h.flush();
    expect(events.every((e) => e.identity.userId === "customer_123")).toBe(true);
  });
});

describe("invalid events are refused before they reach the network", () => {
  it("does not send a product_viewed with no productId", async () => {
    const h = harness();
    h.client.product.viewed({ productId: "" });

    expect(await h.flush()).toHaveLength(0);
    expect(h.errors[0].message).toMatch(/commerce.productId/);
  });

  it("does not send a negative-quantity add-to-cart", async () => {
    const h = harness();
    h.client.cart.productAdded({ cartId: "c1", productId: "p1", quantity: -1 });

    expect(await h.flush()).toHaveLength(0);
  });

  it("can be turned off for merchants who validate server-side only", async () => {
    const h = harness({ validateEvents: false });
    h.client.product.viewed({ productId: "" });

    expect(await h.flush()).toHaveLength(1);
  });
});

describe("batching", () => {
  it("flushes automatically once the batch is full", async () => {
    const h = harness({ maxBatchSize: 3 });
    h.client.product.viewed({ productId: "p1" });
    h.client.product.viewed({ productId: "p2" });
    h.client.product.viewed({ productId: "p3" });

    await vi.waitFor(() => expect(h.sent).toHaveLength(3));
  });

  it("sends one request for a whole batch", async () => {
    const h = harness();
    for (let i = 0; i < 5; i++) h.client.product.viewed({ productId: `p${i}` });

    await h.flush();
    expect(h.sent).toHaveLength(5);
  });

  it("reports nothing pending after a successful flush", async () => {
    const h = harness();
    h.client.product.viewed({ productId: "p1" });

    await h.flush();
    expect(h.client.pending()).toBe(0);
  });
});
