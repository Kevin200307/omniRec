import { beforeEach, describe, expect, it, vi } from "vitest";
import { SCHEMA_VERSION, type CommerceEvent } from "../src/events/types";
import { Batcher } from "../src/transport/batcher";
import { OfflineBuffer } from "../src/transport/offlineBuffer";
import { Transport, classify } from "../src/transport/transport";
import { MemoryStore } from "../src/storage/storage";

function event(id: string): CommerceEvent {
  return {
    eventId: id,
    eventType: "product_viewed",
    schemaVersion: SCHEMA_VERSION,
    // Fresh, so the batcher maximum-age filter keeps it.
    timestamp: new Date().toISOString(),
    identity: { anonymousId: "anon_A", userId: null, sessionId: "session_1" },
    context: { platform: "web" },
    commerce: { productId: "p1" },
    properties: {},
  };
}

function transportReturning(...statuses: number[]) {
  let call = 0;
  const fetchImpl = vi.fn(async () => {
    const status = statuses[Math.min(call, statuses.length - 1)];
    call++;
    return new Response(null, { status });
  }) as unknown as typeof fetch;

  return {
    fetchImpl,
    transport: new Transport({
      endpoint: "https://events.example.com",
      apiKey: "pk_test",
      maxRetries: 2,
      retryBaseMs: 1,
      fetchImpl,
    }),
  };
}

beforeEach(() => {
  localStorage.clear();
});

describe("retry classification", () => {
  it("treats 2xx as delivered", () => {
    expect(classify(200).status).toBe("delivered");
    expect(classify(202).status).toBe("delivered");
  });

  it("treats validation and auth failures as permanent", () => {
    expect(classify(400).status).toBe("rejected");
    expect(classify(401).status).toBe("rejected");
    expect(classify(403).status).toBe("rejected");
    expect(classify(413).status).toBe("rejected");
  });

  it("treats 429 and 408 as retryable — the server asked us to back off", () => {
    expect(classify(429).status).toBe("retryable");
    expect(classify(408).status).toBe("retryable");
  });

  it("treats 5xx as retryable", () => {
    expect(classify(500).status).toBe("retryable");
    expect(classify(503).status).toBe("retryable");
  });
});

describe("Transport", () => {
  it("delivers a batch", async () => {
    const { transport } = transportReturning(202);

    expect((await transport.send([event("e1")])).status).toBe("delivered");
  });

  it("sends the public key as a header", async () => {
    const { transport, fetchImpl } = transportReturning(202);
    await transport.send([event("e1")]);

    const init = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>)["X-Omnirec-Key"]).toBe("pk_test");
  });

  it("retries a 500 and succeeds on the next attempt", async () => {
    const { transport, fetchImpl } = transportReturning(500, 202);

    const outcome = await transport.sendWithRetry([event("e1")], async () => {});

    expect(outcome.status).toBe("delivered");
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("gives up after maxRetries on a persistent 500", async () => {
    const { transport, fetchImpl } = transportReturning(500);

    const outcome = await transport.sendWithRetry([event("e1")], async () => {});

    expect(outcome.status).toBe("retryable");
    expect(fetchImpl).toHaveBeenCalledTimes(3); // initial + 2 retries
  });

  it("never retries a 400 — the payload will not become valid", async () => {
    const { transport, fetchImpl } = transportReturning(400);

    const outcome = await transport.sendWithRetry([event("e1")], async () => {});

    expect(outcome.status).toBe("rejected");
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("never retries a 401", async () => {
    const { transport, fetchImpl } = transportReturning(401);

    await transport.sendWithRetry([event("e1")], async () => {});

    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("treats a network failure as retryable", async () => {
    const fetchImpl = vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    }) as unknown as typeof fetch;
    const transport = new Transport({
      endpoint: "https://events.example.com",
      apiKey: "pk_test",
      maxRetries: 0,
      fetchImpl,
    });

    expect((await transport.send([event("e1")])).status).toBe("retryable");
  });

  it("backs off exponentially, with jitter keeping it under the ceiling", () => {
    const transport = new Transport({
      endpoint: "https://events.example.com",
      apiKey: "pk_test",
      retryBaseMs: 100,
      retryMaxMs: 1000,
    });

    expect(transport.backoffMs(0)).toBeLessThanOrEqual(100);
    expect(transport.backoffMs(1)).toBeLessThanOrEqual(200);
    expect(transport.backoffMs(2)).toBeLessThanOrEqual(400);
    expect(transport.backoffMs(10)).toBeLessThanOrEqual(1000);
  });

  it("sends nothing for an empty batch", async () => {
    const { transport, fetchImpl } = transportReturning(202);

    expect((await transport.send([])).status).toBe("delivered");
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});

describe("OfflineBuffer", () => {
  it("round-trips events", () => {
    const buffer = new OfflineBuffer({ store: new MemoryStore() });
    buffer.append([event("e1"), event("e2")]);

    const drained = buffer.drain();

    expect(drained.map((e) => e.eventId)).toEqual(["e1", "e2"]);
    expect(buffer.size()).toBe(0);
  });

  it("is bounded, dropping oldest first", () => {
    const buffer = new OfflineBuffer({ maxEvents: 3, store: new MemoryStore() });

    const dropped = buffer.append([event("e1"), event("e2"), event("e3"), event("e4")]);

    expect(dropped).toBe(1);
    expect(buffer.drain().map((e) => e.eventId)).toEqual(["e2", "e3", "e4"]);
  });

  it("recovers from a corrupt entry instead of wedging", () => {
    const store = new MemoryStore();
    store.set("omnirec_offline_buffer", "{not json");
    const buffer = new OfflineBuffer({ store });

    expect(buffer.drain()).toEqual([]);
    expect(() => buffer.append([event("e1")])).not.toThrow();
  });
});

describe("Batcher failure handling", () => {
  function batcherWith(...statuses: number[]) {
    const { transport, fetchImpl } = transportReturning(...statuses);
    const dropped: Array<{ count: number; reason: string }> = [];
    const batcher = new Batcher(transport, {
      maxBatchSize: 1000,
      autoFlush: false,
      offlineBuffer: new OfflineBuffer({ store: new MemoryStore() }),
      onDrop: (events, reason) => dropped.push({ count: events.length, reason }),
    });
    return { batcher, dropped, fetchImpl };
  }

  it("keeps events buffered when the server is down", async () => {
    const { batcher } = batcherWith(503);
    batcher.enqueue(event("e1"));

    await batcher.flush();

    expect(batcher.pending()).toBe(1);
  });

  it("delivers buffered events once the server recovers", async () => {
    const { transport, fetchImpl } = transportReturning(503, 503, 503, 202);
    const batcher = new Batcher(transport, {
      maxBatchSize: 1000,
      autoFlush: false,
      offlineBuffer: new OfflineBuffer({ store: new MemoryStore() }),
    });
    batcher.enqueue(event("e1"));

    await batcher.flush();
    expect(batcher.pending()).toBe(1);

    await batcher.flush();

    expect(batcher.pending()).toBe(0);
    expect(fetchImpl).toHaveBeenCalled();
  });

  it("drops a permanently rejected batch and reports it", async () => {
    const { batcher, dropped } = batcherWith(400);
    batcher.enqueue(event("e1"));

    await batcher.flush();

    expect(batcher.pending()).toBe(0);
    expect(dropped).toEqual([{ count: 1, reason: "rejected" }]);
  });

  it("sends buffered events ahead of new ones", async () => {
    // Three 503s exhaust the first flush's retries (initial + 2) so the batch
    // lands in the offline buffer; the fourth attempt, on the next flush, succeeds.
    const { transport, fetchImpl } = transportReturning(503, 503, 503, 202);
    const batcher = new Batcher(transport, {
      maxBatchSize: 1000,
      autoFlush: false,
      offlineBuffer: new OfflineBuffer({ store: new MemoryStore() }),
    });
    batcher.enqueue(event("old"));
    await batcher.flush();

    batcher.enqueue(event("new"));
    await batcher.flush();

    const lastCall = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
    const body = JSON.parse(String((lastCall[1] as RequestInit).body));
    expect(body.events.map((e: CommerceEvent) => e.eventId)).toEqual(["old", "new"]);
  });

  it("does nothing on an empty flush", async () => {
    const { batcher, fetchImpl } = batcherWith(202);

    await batcher.flush();

    expect(fetchImpl).not.toHaveBeenCalled();
  });
});
