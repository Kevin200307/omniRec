// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CommerceClient } from "../src/core/client";
import { businessEventId } from "../src/core/emitter";
import { sanitizeUrl } from "../src/context/sanitizeUrl";
import { SCHEMA_VERSION, type CommerceEvent } from "../src/events/types";
import { IdentityManager } from "../src/identity/identityManager";
import { MemoryStore } from "../src/storage/storage";
import { Batcher } from "../src/transport/batcher";
import { OfflineBuffer } from "../src/transport/offlineBuffer";
import { Transport } from "../src/transport/transport";

/**
 * One test (at least) per defect found in the audit (docs/AUDIT.md). Each of
 * these fails against the pre-audit code.
 */

function event(id: string, timestamp = new Date().toISOString()): CommerceEvent {
  return {
    eventId: id,
    event: "product_viewed",
    source: "browser",
    schemaVersion: SCHEMA_VERSION,
    timestamp,
    identity: { anonymousId: "anon_A", userId: null, sessionId: "s1" },
    context: { platform: "web" },
    data: { product: { id: "p1" } },
    properties: {},
  };
}

function recordingTransport(statuses: number[] = [202]) {
  const requests: Array<{ ids: string[]; keepalive: boolean | undefined }> = [];
  let call = 0;
  const fetchImpl = vi.fn(async (_url: string, init?: RequestInit) => {
    requests.push({
      ids: JSON.parse(String(init?.body)).events.map((e: CommerceEvent) => e.eventId),
      keepalive: init?.keepalive,
    });
    const status = statuses[Math.min(call++, statuses.length - 1)];
    return new Response(null, { status });
  }) as unknown as typeof fetch;
  const transport = new Transport({
    endpoint: "https://events.example.com",
    apiKey: "pk_test",
    maxRetries: 0,
    fetchImpl,
  });
  return { transport, requests };
}

beforeEach(() => {
  localStorage.clear();
});

describe("F1 — a backlog is sent in chunks, never one oversized request", () => {
  it("splits 45 buffered events into requests of at most 20", async () => {
    const { transport, requests } = recordingTransport();
    const offline = new OfflineBuffer({ store: new MemoryStore() });
    offline.append(Array.from({ length: 45 }, (_, i) => event(`e${i}`)));
    const batcher = new Batcher(transport, { maxBatchSize: 20, autoFlush: false, offlineBuffer: offline });

    await batcher.flush();

    expect(requests.map((r) => r.ids.length)).toEqual([20, 20, 5]);
    expect(batcher.pending()).toBe(0);
  });

  it("drops only the rejected chunk, and still delivers the rest", async () => {
    const { transport, requests } = recordingTransport([400, 202]);
    const dropped: string[] = [];
    const offline = new OfflineBuffer({ store: new MemoryStore() });
    offline.append(Array.from({ length: 4 }, (_, i) => event(`e${i}`)));
    const batcher = new Batcher(transport, {
      maxBatchSize: 2,
      autoFlush: false,
      offlineBuffer: offline,
      onDrop: (events, reason) => dropped.push(...events.map((e) => `${e.eventId}:${reason}`)),
    });

    await batcher.flush();

    expect(requests).toHaveLength(2);
    expect(dropped).toEqual(["e0:rejected", "e1:rejected"]);
    expect(batcher.pending()).toBe(0);
  });
});

describe("F2 — keepalive only where browsers allow it", () => {
  it("uses keepalive for a small batch", async () => {
    const { transport, requests } = recordingTransport();
    await transport.send([event("e1")]);
    expect(requests[0].keepalive).toBe(true);
  });

  it("drops keepalive for a body over 64KB, which browsers would refuse outright", async () => {
    const { transport, requests } = recordingTransport();
    const big = Array.from({ length: 200 }, (_, i) => ({ ...event(`e${i}`), properties: { note: "x".repeat(400) } }));
    await transport.send(big);
    expect(requests[0].keepalive).toBe(false);
  });
});

describe("F3 — failing flushes back off instead of hammering the API", () => {
  it("skips timer-driven flushes during backoff, but honours an explicit flush", async () => {
    let now = 1_000_000;
    const { transport, requests } = recordingTransport([503]);
    const batcher = new Batcher(transport, {
      autoFlush: false,
      maxWaitMs: 5000,
      offlineBuffer: new OfflineBuffer({ store: new MemoryStore() }),
      now: () => now,
    });
    vi.spyOn(Math, "random").mockReturnValue(1);

    batcher.enqueue(event("e1"));
    await batcher.flush({ force: false });
    expect(requests).toHaveLength(1);
    expect(batcher.backoffRemainingMs()).toBeGreaterThan(0);

    await batcher.flush({ force: false });
    expect(requests).toHaveLength(1);

    await batcher.flush({ force: true });
    expect(requests).toHaveLength(2);

    now += 10 * 60 * 1000;
    await batcher.flush({ force: false });
    expect(requests).toHaveLength(3);
  });

  it("grows the backoff but caps it", async () => {
    let now = 0;
    const { transport } = recordingTransport([503]);
    const batcher = new Batcher(transport, {
      autoFlush: false,
      maxWaitMs: 1000,
      maxBackoffMs: 8000,
      offlineBuffer: new OfflineBuffer({ store: new MemoryStore() }),
      now: () => now,
    });
    vi.spyOn(Math, "random").mockReturnValue(1);
    batcher.enqueue(event("e1"));

    const waits: number[] = [];
    for (let i = 0; i < 6; i++) {
      await batcher.flush();
      waits.push(batcher.backoffRemainingMs());
    }

    expect(waits).toEqual([1000, 2000, 4000, 8000, 8000, 8000]);
  });
});

describe("F4 — events too old to deduplicate are dropped, not resent", () => {
  it("drops buffered events older than maxEventAgeMs", async () => {
    const { transport, requests } = recordingTransport();
    const dropped: string[] = [];
    const offline = new OfflineBuffer({ store: new MemoryStore() });
    const old = new Date(Date.now() - 13 * 60 * 60 * 1000).toISOString();
    offline.append([event("stale", old), event("fresh")]);
    const batcher = new Batcher(transport, {
      autoFlush: false,
      offlineBuffer: offline,
      onDrop: (events, reason) => dropped.push(...events.map((e) => `${e.eventId}:${reason}`)),
    });

    await batcher.flush();

    expect(requests[0].ids).toEqual(["fresh"]);
    expect(dropped).toEqual(["stale:expired"]);
  });
});

describe("F5 — a batch mid-retry survives a page unload", () => {
  it("beacons events that were in flight when the page went away", async () => {
    let release: () => void = () => {};
    const fetchImpl = vi.fn(
      () => new Promise<Response>((resolve) => (release = () => resolve(new Response(null, { status: 503 }))))
    ) as unknown as typeof fetch;
    const transport = new Transport({ endpoint: "https://events.example.com", apiKey: "pk_test", maxRetries: 0, fetchImpl });
    const beaconed: string[] = [];
    vi.spyOn(transport, "sendBeacon").mockImplementation((events) => {
      beaconed.push(...events.map((e) => e.eventId));
      return true;
    });
    const batcher = new Batcher(transport, { autoFlush: false, offlineBuffer: new OfflineBuffer({ store: new MemoryStore() }) });

    batcher.enqueue(event("in_flight"));
    const flushing = batcher.flush(); // the fetch is now pending
    batcher.flushOnUnload(); // ...and the page goes away

    expect(beaconed).toEqual(["in_flight"]);
    release();
    await flushing;
  });
});

describe("B3 — the browser and the backend agree on a purchase's eventId", () => {
  function harness() {
    const sent: CommerceEvent[] = [];
    const fetchImpl = vi.fn(async (_url: string, init?: RequestInit) => {
      sent.push(...JSON.parse(String(init?.body)).events);
      return new Response(null, { status: 202 });
    }) as unknown as typeof fetch;
    const client = new CommerceClient({
      apiKey: "pk_test",
      endpoint: "https://events.example.com",
      autoTrackSessions: false,
      maxBatchSize: 1000,
      fetchImpl,
    });
    return { client, sent };
  }

  it("derives the eventId from the orderId, so a reload or retry deduplicates", async () => {
    const { client, sent } = harness();
    const purchase = { orderId: "order_1", items: [{ productId: "p1", quantity: 1 }], total: 10, currency: "USD" };

    client.purchase.completed(purchase);
    client.purchase.completed(purchase);
    await client.flush();

    expect(sent.map((e) => e.eventId)).toEqual(["evt:purchase_completed:order_1", "evt:purchase_completed:order_1"]);
    client.destroy();
  });

  it("uses exactly the id the Java SDK produces for the same order", () => {
    // Asserted byte-for-byte on the Java side too (CommerceTrackerTest).
    expect(businessEventId("purchase_completed", "order_1")).toBe("evt:purchase_completed:order_1");
    expect(businessEventId("order_refunded", "order_1")).toBe("evt:order_refunded:order_1");
  });
});

describe("I1 — a different user signing in gets a fresh session", () => {
  it("rotates the session when one user replaces another without logging out", () => {
    const identity = new IdentityManager({ anonymousStore: new MemoryStore(), sessionStore: new MemoryStore() });
    identity.identify("customer_1");
    const first = identity.current().sessionId;

    identity.identify("customer_2");

    expect(identity.current().sessionId).not.toBe(first);
    expect(identity.current().userId).toBe("customer_2");
  });

  it("keeps the session when an anonymous visitor logs in", () => {
    const identity = new IdentityManager({ anonymousStore: new MemoryStore(), sessionStore: new MemoryStore() });
    const anonymousSession = identity.current().sessionId;

    identity.identify("customer_1");

    // Keeping it is what lets providers stitch pre-login browsing to the user.
    expect(identity.current().sessionId).toBe(anonymousSession);
  });
});

describe("A6 — secrets and personal data are scrubbed from URLs", () => {
  it.each([
    ["https://shop.example/reset?token=abc123&utm_source=mail", "https://shop.example/reset?utm_source=mail"],
    ["https://shop.example/cb?code=xyz&state=s", "https://shop.example/cb"],
    ["https://shop.example/p/1?email=a%40b.com&ref=home", "https://shop.example/p/1?ref=home"],
    ["https://shop.example/p/1?contact=a%40b.com", "https://shop.example/p/1"],
    ["https://user:pass@shop.example/p/1", "https://shop.example/p/1"],
    ["https://shop.example/#access_token=secret", "https://shop.example/"],
    ["https://shop.example/p/1?x_csrf_token=1", "https://shop.example/p/1"],
  ])("sanitizes %s", (input, expected) => {
    expect(sanitizeUrl(input)).toBe(expected);
  });

  it("keeps harmless parameters", () => {
    expect(sanitizeUrl("https://shop.example/search?q=laptop&page=2")).toBe(
      "https://shop.example/search?q=laptop&page=2"
    );
  });

  it("falls back to dropping the query of an unparseable URL", () => {
    expect(sanitizeUrl("/relative?token=abc")).toBe("/relative");
  });
});
