// SPDX-License-Identifier: Apache-2.0
import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { createOmnirecServer, identityFromCookies, type OmnirecServer } from "../src";

interface Received {
  headers: IncomingMessage["headers"];
  events: Array<Record<string, any>>;
}

let server: Server;
let endpoint: string;
let received: Received[];
let statuses: number[];
const clients: OmnirecServer[] = [];

beforeEach(async () => {
  received = [];
  statuses = [];
  server = createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      const status = statuses.shift() ?? 202;
      if (status < 300) received.push({ headers: req.headers, events: JSON.parse(body).events });
      res.writeHead(status, { "content-type": "application/json" }).end("{}");
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  endpoint = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterEach(async () => {
  for (const c of clients.splice(0)) await c.close();
  await new Promise((resolve) => server.close(resolve));
});

function client(overrides: Record<string, unknown> = {}) {
  const errors: Error[] = [];
  const c = createOmnirecServer({ endpoint, flushIntervalMs: 0, retryBaseMs: 1, onError: (e) => errors.push(e), ...overrides });
  clients.push(c);
  return { c, errors };
}

const visitor = { anonymousId: "anon_A", sessionId: "s1" };

describe("createOmnirecServer", () => {
  it("sends v2 server events with the visitor's identity", async () => {
    const { c } = client();
    const id = c.track(
      "purchase_completed",
      { order: { id: "o1", currency: "USD", total: "24.00", items: [{ productId: "P1", quantity: 2 }] } },
      { identity: { ...visitor, userId: "c_1" }, eventId: "evt:purchase_completed:o1" }
    );
    await c.flush();
    const [event] = received[0].events;
    expect(id).toBe("evt:purchase_completed:o1");
    expect(event).toMatchObject({
      eventId: id,
      event: "purchase_completed",
      schemaVersion: "2.0",
      source: "server",
      identity: { anonymousId: "anon_A", sessionId: "s1", userId: "c_1" },
      context: { platform: "server" },
      data: { order: { id: "o1", total: "24.00" } },
    });
  });

  it("sends the key only when configured", async () => {
    const keyless = client();
    keyless.c.track("page_viewed", undefined, { identity: visitor });
    await keyless.c.flush();
    expect(received[0].headers["x-omnirec-key"]).toBeUndefined();

    const keyed = client({ apiKey: "sk_server" });
    keyed.c.track("page_viewed", undefined, { identity: visitor });
    await keyed.c.flush();
    expect(received[1].headers["x-omnirec-key"]).toBe("sk_server");
  });

  it("batches and resolves flush once everything is sent", async () => {
    const { c } = client({ maxBatchSize: 2 });
    for (let i = 0; i < 5; i++) c.track("page_viewed", undefined, { identity: visitor });
    await c.flush();
    expect(received.map((r) => r.events.length)).toEqual([2, 2, 1]);
    expect(c.pending()).toBe(0);
  });

  it("retries 503 and succeeds", async () => {
    statuses = [503, 503];
    const { c, errors } = client();
    c.track("page_viewed", undefined, { identity: visitor });
    await c.flush();
    expect(received).toHaveLength(1);
    expect(errors).toEqual([]);
  });

  it("does not retry a 400; reports it", async () => {
    statuses = [400];
    const { c, errors } = client();
    c.track("page_viewed", undefined, { identity: visitor });
    await c.flush();
    expect(received).toHaveLength(0);
    expect(errors[0].message).toMatch(/HTTP 400/);
  });

  it("gives up after the retry budget and reports it", async () => {
    statuses = [503, 503, 503];
    const { c, errors } = client({ maxRetries: 2 });
    c.track("page_viewed", undefined, { identity: visitor });
    await c.flush();
    expect(errors[0].message).toMatch(/after 3 attempts/);
  });

  it("falls back to the userId when there is no browser identity, and invents a server session", async () => {
    const { c } = client();
    c.track("order_cancelled", { order: { id: "o1" } }, { identity: { userId: "c_9" } });
    await c.flush();
    const [event] = received[0].events;
    expect(event.identity.anonymousId).toBe("user:c_9");
    expect(event.identity.sessionId).toMatch(/^server_/);
  });

  it("refuses an event with no identity at all", () => {
    const { c } = client();
    expect(() => c.track("page_viewed", undefined, { identity: {} })).toThrow(/anonymousId/);
  });

  it("links a visitor with identify()", async () => {
    const { c } = client();
    c.identify(visitor, "c_1", { tier: "gold" });
    await c.flush();
    expect(received[0].events[0]).toMatchObject({
      event: "identify",
      identity: { anonymousId: "anon_A", userId: "c_1" },
      properties: { traits: { tier: "gold" } },
    });
  });

  it("rejects a relative endpoint", () => {
    expect(() => createOmnirecServer({ endpoint: "/omnirec" })).toThrow(/absolute/);
  });
});

describe("identityFromCookies", () => {
  it("reads a Cookie header", () => {
    expect(identityFromCookies("a=1; omnirec_anonymous_id=anon%20A; omnirec_session_id=s1")).toEqual({
      anonymousId: "anon A",
      sessionId: "s1",
    });
  });

  it("reads a cookie map and tolerates missing values", () => {
    expect(identityFromCookies({ omnirec_anonymous_id: "anon_A" })).toEqual({ anonymousId: "anon_A", sessionId: null });
    expect(identityFromCookies(undefined)).toEqual({});
  });
});
