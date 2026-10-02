// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createOmnirec, type OmnirecClient } from "../src/core/omnirec";
import type { Middleware, OmnirecPlugin } from "../src/core/pipeline";
import type { CommerceEvent } from "../src/events/types";

interface Call {
  url: string;
  headers: Record<string, string>;
  events: CommerceEvent[];
}

function harness(config: Record<string, unknown> = {}) {
  const calls: Call[] = [];
  const fetchImpl = vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({
      url,
      headers: (init?.headers ?? {}) as Record<string, string>,
      events: JSON.parse(String(init?.body)).events,
    });
    return new Response(null, { status: 202 });
  }) as unknown as typeof fetch;
  const errors: Error[] = [];
  const client = createOmnirec({
    endpoint: "https://events.example.com",
    autoTrackSessions: false,
    maxBatchSize: 1000,
    fetchImpl,
    onError: (e) => errors.push(e),
    ...config,
  });
  return {
    client,
    calls,
    errors,
    async sent(): Promise<CommerceEvent[]> {
      await client.flush();
      return calls.flatMap((c) => c.events);
    },
  };
}

function clearCookies() {
  for (const cookie of document.cookie.split(";")) {
    const name = cookie.split("=")[0].trim();
    if (name) document.cookie = `${name}=; path=/; max-age=0`;
  }
}

beforeEach(() => {
  localStorage.clear();
  clearCookies();
});

describe("createOmnirec", () => {
  it("sends without a key header when no apiKey is configured", async () => {
    const h = harness();
    h.client.track("product_viewed", { product: { id: "P100" } });
    await h.sent();
    expect(h.calls[0].headers["X-Omnirec-Key"]).toBeUndefined();
    expect(h.calls[0].url).toBe("https://events.example.com/v1/events/batch");
  });

  it("sends the key header when one is configured", async () => {
    const h = harness({ apiKey: "pk_test_1" });
    h.client.track("page_viewed");
    await h.sent();
    expect(h.calls[0].headers["X-Omnirec-Key"]).toBe("pk_test_1");
  });

  it("posts to a same-site path for a proxied collector", async () => {
    const h = harness({ endpoint: "/omnirec" });
    h.client.track("page_viewed");
    await h.sent();
    expect(h.calls[0].url).toBe("/omnirec/v1/events/batch");
  });

  it("sends envelope v2", async () => {
    const h = harness();
    const id = h.client.track(
      "product_added_to_cart",
      { product: { id: "P100", quantity: 2, price: "12.50", currency: "USD" }, cart: { id: "c1" } },
      { properties: { position: 3 } }
    );
    const [event] = await h.sent();
    expect(event).toMatchObject({
      eventId: id,
      event: "product_added_to_cart",
      schemaVersion: "2.0",
      source: "browser",
      data: { product: { id: "P100", quantity: 2, price: "12.50", currency: "USD" }, cart: { id: "c1" } },
      properties: { position: 3 },
    });
    expect(event.identity.anonymousId).toBeTruthy();
    expect(event.identity.sessionId).toBeTruthy();
    expect(event.context.platform).toBe("web");
    expect(event).not.toHaveProperty("eventType");
    expect(event).not.toHaveProperty("commerce");
  });

  it("refuses an event missing a required field and says which", async () => {
    const h = harness();
    // Runtime check for callers that bypass the types (plain JS, untyped names).
    expect(h.client.trackUntyped("product_added_to_cart", { product: { id: "P100" } })).toBeUndefined();
    expect(await h.sent()).toEqual([]);
    expect(h.errors[0].message).toMatch(/data\.product\.quantity/);
  });

  it("sends custom events the built-in catalog does not know; the server checks the plan", async () => {
    const h = harness();
    expect(h.client.trackUntyped("action_x_clicked", { variant: "a" })).toBeDefined();
    const [event] = await h.sent();
    expect(event.event).toBe("action_x_clicked");
    expect(event.data).toEqual({ variant: "a" });
  });

  it("emits identify once per user, carrying traits", async () => {
    const h = harness();
    h.client.identify({ userId: "c_1", traits: { tier: "gold" } });
    h.client.identify("c_1");
    const events = (await h.sent()).filter((e) => e.event === "identify");
    expect(events).toHaveLength(1);
    expect(events[0].identity.userId).toBe("c_1");
    expect(events[0].properties.traits).toEqual({ tier: "gold" });
  });

  it("emits session_started automatically when enabled", async () => {
    const h = harness({ autoTrackSessions: true });
    expect((await h.sent()).map((e) => e.event)).toEqual(["session_started"]);
  });
});

describe("session cookie mirror", () => {
  it("mirrors the session id into a first-party cookie for server SDKs", () => {
    const h = harness();
    const { sessionId } = h.client.getIdentity();
    expect(document.cookie).toContain(`omnirec_session_id=${sessionId}`);
  });

  it("rotates the cookie with the session on logout", () => {
    const h = harness();
    h.client.identify("c_1");
    const before = h.client.getIdentity().sessionId;
    h.client.logout();
    const after = h.client.getIdentity().sessionId;
    expect(after).not.toBe(before);
    expect(document.cookie).toContain(`omnirec_session_id=${after}`);
  });

  it("restores the cookie if it was cleared while the session is still live", () => {
    const h = harness();
    const { sessionId } = h.client.getIdentity();
    document.cookie = "omnirec_session_id=; path=/; max-age=0";
    h.client.track("page_viewed");
    expect(document.cookie).toContain(`omnirec_session_id=${sessionId}`);
  });
});

describe("middleware pipeline", () => {
  it("runs before-validate, validation, then after-validate, in order", async () => {
    const order: string[] = [];
    const before: OmnirecPlugin = {
      name: "before",
      phase: "before-validate",
      middleware: (event, next) => {
        order.push("before");
        next(event);
      },
    };
    const after: Middleware = (event, next) => {
      order.push("after");
      next(event);
    };
    const h = harness({ plugins: [before], middleware: [after] });
    h.client.track("page_viewed");
    expect(order).toEqual(["before", "after"]);
    // After-validate middleware never sees invalid events.
    h.client.trackUntyped("product_viewed", {});
    expect(order).toEqual(["before", "after", "before"]);
  });

  it("lets middleware modify and drop events", async () => {
    const enrich: Middleware = (event, next) => next({ ...event, properties: { ...event.properties, build: "42" } });
    const dropInternal: Middleware = (event, next) => {
      if (event.properties.internal) return;
      next(event);
    };
    const h = harness({ middleware: [enrich, dropInternal] });
    h.client.track("page_viewed");
    h.client.track("page_viewed", undefined, { properties: { internal: true } });
    const events = await h.sent();
    expect(events).toHaveLength(1);
    expect(events[0].properties.build).toBe("42");
  });

  it("lets middleware delay events", async () => {
    let release: (() => void) | undefined;
    const hold: Middleware = (event, next) => {
      release = () => next(event);
    };
    const h = harness({ middleware: [hold] });
    h.client.track("page_viewed");
    expect(await h.sent()).toEqual([]);
    release!();
    expect((await h.sent()).map((e) => e.event)).toEqual(["page_viewed"]);
  });

  it("reports a throwing middleware without breaking later events", async () => {
    let fail = true;
    const flaky: Middleware = (event, next) => {
      if (fail) {
        fail = false;
        throw new Error("boom");
      }
      next(event);
    };
    const h = harness({ middleware: [flaky] });
    h.client.track("page_viewed");
    h.client.track("home_page_viewed");
    expect((await h.sent()).map((e) => e.event)).toEqual(["home_page_viewed"]);
    expect(h.errors[0].message).toBe("boom");
  });

  it("never duplicates an event when middleware calls next twice", async () => {
    const twice: Middleware = (event, next) => {
      next(event);
      next(event);
    };
    const h = harness({ middleware: [twice] });
    h.client.track("page_viewed");
    expect(await h.sent()).toHaveLength(1);
  });
});

describe("plugins", () => {
  it("installs once, can track through the host, and tears down on destroy", async () => {
    const teardown = vi.fn();
    let tracked: string | undefined;
    const plugin: OmnirecPlugin = {
      name: "probe",
      setup(host) {
        tracked = host.track("page_viewed");
        return teardown;
      },
    };
    const h = harness();
    h.client.use(plugin).use(plugin);
    expect(tracked).toBeDefined();
    expect(await h.sent()).toHaveLength(1);
    h.client.destroy();
    expect(teardown).toHaveBeenCalledTimes(1);
  });

  it("ignores tracking after destroy and says so", () => {
    const h = harness();
    h.client.destroy();
    expect(h.client.track("page_viewed")).toBeUndefined();
    expect(h.errors[0].message).toMatch(/destroyed/);
  });
});

export type { OmnirecClient };
