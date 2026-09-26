// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it, vi } from "vitest";
import { DwellTimeTracker } from "../src/dwell/dwellTimeTracker";

function clock(start = 0) {
  let current = start;
  return {
    now: () => current,
    advance(ms: number) {
      current += ms;
    },
  };
}

describe("DwellTimeTracker", () => {
  it("reports foreground time when the visitor leaves the product", () => {
    const time = clock();
    const onFlush = vi.fn();
    const tracker = new DwellTimeTracker(onFlush, { now: time.now, minDwellMs: 1000 });

    tracker.start("p1");
    time.advance(42_500);
    tracker.flush();

    expect(onFlush).toHaveBeenCalledWith("p1", 42_500, {}, null);
  });

  it("ignores a bounce below the minimum", () => {
    const time = clock();
    const onFlush = vi.fn();
    const tracker = new DwellTimeTracker(onFlush, { now: time.now, minDwellMs: 1000 });

    tracker.start("p1");
    time.advance(200);
    tracker.flush();

    expect(onFlush).not.toHaveBeenCalled();
  });

  it("does not count time while the tab is hidden", () => {
    const time = clock();
    const onFlush = vi.fn();
    const tracker = new DwellTimeTracker(onFlush, { now: time.now, minDwellMs: 100 });

    tracker.start("p1");
    time.advance(5_000);
    tracker.pause(); // tab hidden
    time.advance(600_000); // ten minutes in the background
    tracker.resume(); // tab visible again
    time.advance(3_000);
    tracker.flush();

    expect(onFlush).toHaveBeenCalledWith("p1", 8_000, {}, null);
  });

  it("caps an implausibly long dwell", () => {
    const time = clock();
    const onFlush = vi.fn();
    const tracker = new DwellTimeTracker(onFlush, { now: time.now, maxDwellMs: 30 * 60 * 1000 });

    tracker.start("p1");
    time.advance(8 * 60 * 60 * 1000); // tab left open overnight
    tracker.flush();

    expect(onFlush).toHaveBeenCalledWith("p1", 30 * 60 * 1000, {}, null);
  });

  it("flushes the previous product when a new one is viewed", () => {
    const time = clock();
    const onFlush = vi.fn();
    const tracker = new DwellTimeTracker(onFlush, { now: time.now, minDwellMs: 100 });

    tracker.start("p1");
    time.advance(3_000);
    tracker.start("p2");

    expect(onFlush).toHaveBeenCalledWith("p1", 3_000, {}, null);
    expect(tracker.currentProductId()).toBe("p2");
  });

  it("keeps accumulating when the same product is viewed again", () => {
    const time = clock();
    const onFlush = vi.fn();
    const tracker = new DwellTimeTracker(onFlush, { now: time.now, minDwellMs: 100 });

    tracker.start("p1");
    time.advance(2_000);
    tracker.start("p1");
    time.advance(2_000);
    tracker.flush();

    expect(onFlush).toHaveBeenCalledTimes(1);
    expect(onFlush).toHaveBeenCalledWith("p1", 4_000, {}, null);
  });

  it("carries the product's commerce fields through to the dwell event", () => {
    const time = clock();
    const onFlush = vi.fn();
    const tracker = new DwellTimeTracker(onFlush, { now: time.now, minDwellMs: 100 });

    tracker.start("p1", { categoryId: "laptops", price: 1500, currency: "USD" });
    time.advance(2_000);
    tracker.flush();

    expect(onFlush).toHaveBeenCalledWith(
      "p1",
      2_000,
      {
      categoryId: "laptops",
      price: 1500,
      currency: "USD",
      },
      null
    );
  });

  it("flushes only once — a second flush is a no-op", () => {
    const time = clock();
    const onFlush = vi.fn();
    const tracker = new DwellTimeTracker(onFlush, { now: time.now, minDwellMs: 100 });

    tracker.start("p1");
    time.advance(2_000);
    tracker.flush();
    tracker.flush();

    expect(onFlush).toHaveBeenCalledTimes(1);
  });

  it("does nothing when no product is being measured", () => {
    const onFlush = vi.fn();
    const tracker = new DwellTimeTracker(onFlush);

    tracker.flush();

    expect(onFlush).not.toHaveBeenCalled();
  });

  it("emits no heartbeat events while measuring", () => {
    const time = clock();
    const onFlush = vi.fn();
    const tracker = new DwellTimeTracker(onFlush, { now: time.now, minDwellMs: 100 });

    tracker.start("p1");
    for (let i = 0; i < 60; i++) time.advance(1_000);

    // A full minute of viewing produces zero events until the visitor leaves.
    expect(onFlush).not.toHaveBeenCalled();
    tracker.flush();
    expect(onFlush).toHaveBeenCalledTimes(1);
  });
});

describe("DwellTimeTracker integrated with the client", () => {
  async function clientHarness() {
    // destroy() persists unsent events for the next page load; start clean.
    localStorage.clear();
    const { CommerceClient } = await import("../src/core/client");
    const sent: Array<Record<string, any>> = [];
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
    return { client, sent, viewed: () => sent.filter((e) => e.eventType === "product_viewed") };
  }

  it("sends the dwell as an engagement update that points at its view", async () => {
    const { client, viewed } = await clientHarness();

    client.product.viewed({ productId: "p1", price: 10, currency: "USD" });
    await new Promise((resolve) => setTimeout(resolve, 1100));
    client.product.viewEnded();
    await client.flush();

    const [view, update] = viewed();
    expect(view.properties).toEqual({});
    expect(update.properties.dwellTimeMs).toBeGreaterThan(1000);
    // The link that lets interaction-counting destinations skip the update,
    // so each view is counted once rather than twice.
    expect(update.properties.viewEventId).toBe(view.eventId);
    client.destroy();
  });

  it("does not end the measurement just because events were flushed", async () => {
    const { client, viewed } = await clientHarness();

    client.product.viewed({ productId: "p1" });
    await new Promise((resolve) => setTimeout(resolve, 1100));
    await client.flush();

    expect(viewed()).toHaveLength(1);
    client.destroy();
  });

  it("ends the measurement on an SPA route change, so later pages don't count", async () => {
    const { client, viewed } = await clientHarness();

    client.product.viewed({ productId: "p1" });
    await new Promise((resolve) => setTimeout(resolve, 1100));
    client.page.viewed();
    await client.flush();
    expect(viewed()).toHaveLength(2);
    const dwellAtNavigation = viewed()[1].properties.dwellTimeMs;

    // Time on the next page must not be attributed to the product.
    await new Promise((resolve) => setTimeout(resolve, 1100));
    client.product.viewEnded();
    await client.flush();

    expect(viewed()).toHaveLength(2);
    expect(dwellAtNavigation).toBeLessThan(2000);
    client.destroy();
  });
});
