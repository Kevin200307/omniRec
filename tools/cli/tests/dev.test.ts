// SPDX-License-Identifier: Apache-2.0
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { startDevServer, type DevServer } from "../src";

const PLAN = `
events:
  quiz_completed:
    properties:
      score: { type: integer, required: true, minimum: 0 }
`;

function event(name: string, data: Record<string, unknown> = {}, id = `e_${Math.random().toString(36).slice(2)}`) {
  return {
    eventId: id,
    event: name,
    schemaVersion: "2.0",
    source: "browser",
    timestamp: new Date().toISOString(),
    identity: { anonymousId: "anon_1", sessionId: "s1" },
    context: {},
    data,
  };
}

describe("omnirec dev", () => {
  let dev: DevServer;
  const lines: string[] = [];

  beforeAll(async () => {
    const cwd = mkdtempSync(join(tmpdir(), "omnirec-dev-"));
    writeFileSync(join(cwd, "omnirec.plan.yaml"), PLAN);
    dev = await startDevServer({ cwd, port: 0, color: false, watch: false, log: (l) => lines.push(l) });
  });

  afterAll(async () => {
    await dev.close();
  });

  const post = (path: string, body: unknown, contentType = "application/json") =>
    fetch(dev.url + path, { method: "POST", headers: { "Content-Type": contentType }, body: JSON.stringify(body) });

  it("accepts valid catalog, alias and plan events and prints them", async () => {
    const res = await post("/v1/events", {
      events: [
        event("product_viewed", { product: { id: "P1" } }),
        event("add_to_cart", { product: { id: "P1", quantity: 2 } }),
        event("quiz_completed", { score: 7 }),
      ],
    });
    expect(res.status).toBe(202);
    expect(await res.json()).toMatchObject({ accepted: 3, rejected: 0 });
    expect(lines.some((l) => l.includes("✓ add_to_cart (alias of product_added_to_cart)"))).toBe(true);
    expect(lines.some((l) => l.includes("✓ quiz_completed"))).toBe(true);
  });

  it("rejects what the collector rejects, naming the field", async () => {
    const res = await post("/v1/events/batch", {
      events: [
        event("product_added_to_cart", { product: { id: "P1", quantity: 0 } }),
        event("quiz_completed", {}),
        event("purchase_completed", { order: { id: "o1", total: 10, currency: "usd", items: [{ productId: "p1" }] } }),
        { ...event("product_viewed", { product: { id: "P1" } }), properties: { cardNumber: "4111" } },
        event("product_viewed", { product: {} }),
      ],
    });
    const body: any = await res.json();
    expect(body.accepted).toBe(0);
    expect(body.rejected).toBe(5);
    const reasons = body.errors.map((e: { reason: string }) => e.reason).join("\n");
    expect(reasons).toContain("data.product.quantity: quantity must be greater than 0");
    expect(reasons).toContain("data.score: score is required");
    expect(reasons).toContain("data.order.currency: currency must be a 3-letter ISO 4217 code");
    expect(reasons).toContain("properties.cardNumber");
    expect(reasons).toContain("data.product.id: id is required");
  });

  it("accepts unknown events as unplanned, and spots duplicates", async () => {
    const res = await post("/v1/events", { events: [event("made_up_thing"), event("product_viewed", { product: { id: "P2" } }, "dup_1")] });
    expect(await res.json()).toMatchObject({ accepted: 2 });
    expect(lines.some((l) => l.includes("made_up_thing") && l.includes("unplanned"))).toBe(true);

    const again = await post("/v1/events", { events: [event("product_viewed", { product: { id: "P2" } }, "dup_1")] });
    expect(await again.json()).toMatchObject({ accepted: 0, duplicates: 1 });
  });

  it("takes sendBeacon's text/plain bodies and answers CORS preflights from any origin", async () => {
    const res = await post("/v1/events", { events: [event("page_viewed")] }, "text/plain");
    expect(res.status).toBe(202);
    const preflight = await fetch(dev.url + "/v1/events", { method: "OPTIONS", headers: { Origin: "http://shop.test" } });
    expect(preflight.status).toBe(204);
    expect(preflight.headers.get("access-control-allow-origin")).toBe("*");
  });

  it("serves the catalog with the plan, the identify shim, the live list and its data", async () => {
    const catalog: any = await (await fetch(dev.url + "/v1/catalog")).json();
    expect(catalog.events.find((e: { name: string }) => e.name === "quiz_completed")).toMatchObject({ kind: "custom" });
    expect(catalog.events.length).toBeGreaterThan(200);

    expect((await post("/v1/identify", { anonymousId: "anon_1", userId: "u1" })).status).toBe(202);
    expect((await post("/v1/identify", { anonymousId: "anon_1" })).status).toBe(400);

    const page = await (await fetch(dev.url + "/")).text();
    expect(page).toContain("<title>omnirec dev</title>");
    const received: any = await (await fetch(dev.url + "/__events")).json();
    expect(received[0]).toHaveProperty("status");
    expect(received.some((r: { status: string }) => r.status === "rejected")).toBe(true);
  });

  it("answers 400 for a body that is not JSON and 404 elsewhere", async () => {
    const bad = await fetch(dev.url + "/v1/events", { method: "POST", body: "{nope" });
    expect(bad.status).toBe(400);
    expect((await fetch(dev.url + "/v1/other")).status).toBe(404);
  });
});
