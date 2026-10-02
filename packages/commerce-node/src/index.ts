// SPDX-License-Identifier: Apache-2.0
import { randomUUID } from "node:crypto";
import type { CommerceEvent, KnownEvents } from "@omnirec/commerce-web";

/** Who a server-side event belongs to. Take it from the visitor's cookies when you can. */
export interface ServerIdentity {
  /** The browser's `omnirec_anonymous_id`. Ties server events to the same visitor. */
  anonymousId?: string | null;
  /** The browser's `omnirec_session_id`. Ties server events to the same session. */
  sessionId?: string | null;
  /** Your own customer id, when known. */
  userId?: string | null;
}

export interface ServerTrackOptions {
  identity: ServerIdentity;
  properties?: Record<string, unknown>;
  /** A deterministic id (for example from an order id) so a retried fact deduplicates. */
  eventId?: string;
  /** When it happened. Defaults to now. */
  timestamp?: Date | string;
}

export interface OmnirecServerConfig {
  /** Absolute collector URL, for example "https://events.example.com". */
  endpoint: string;
  /** Publishable or secret key, when the collector runs in keys mode. */
  apiKey?: string;
  /** Events per request. Default 50. */
  maxBatchSize?: number;
  /** Send queued events at least this often, in ms. Default 1000. 0 sends only on flush(). */
  flushIntervalMs?: number;
  /** Retries for 5xx, 408, 429 and network errors. Default 3. */
  maxRetries?: number;
  /** First backoff step in ms; doubles each attempt, with full jitter. Default 200. */
  retryBaseMs?: number;
  fetchImpl?: typeof fetch;
  /** Rejected batches and exhausted retries. Defaults to console.warn. */
  onError?: (error: Error) => void;
}

type TrackArgs<E extends keyof KnownEvents> = {} extends KnownEvents[E]
  ? [data: KnownEvents[E] | undefined, options: ServerTrackOptions]
  : [data: KnownEvents[E], options: ServerTrackOptions];

const COOKIE_ANON = "omnirec_anonymous_id";
const COOKIE_SESSION = "omnirec_session_id";

/**
 * Reads the visitor identity the browser SDK leaves in first-party cookies.
 * Accepts a raw `Cookie` header or a name-to-value map.
 */
export function identityFromCookies(cookies: string | Record<string, string | undefined> | undefined | null): ServerIdentity {
  if (!cookies) return {};
  const read = (name: string): string | undefined => {
    if (typeof cookies !== "string") return cookies[name] ?? undefined;
    const match = cookies.match(new RegExp(`(?:^|;\\s*)${name}=([^;]*)`));
    if (!match) return undefined;
    try {
      return decodeURIComponent(match[1]);
    } catch {
      return match[1];
    }
  };
  return { anonymousId: read(COOKIE_ANON) ?? null, sessionId: read(COOKIE_SESSION) ?? null };
}

/**
 * Sends events from a server. Batches, retries transient failures with
 * backoff, and drops (and reports) batches the collector rejects outright.
 * Call `flush()` before a serverless function returns.
 *
 *     const omnirec = createOmnirecServer({ endpoint: process.env.OMNIREC_ENDPOINT! });
 *     omnirec.track("purchase_completed", { order }, { identity: identityFromCookies(req.headers.cookie) });
 *     await omnirec.flush();
 */
export class OmnirecServer {
  private readonly endpoint: string;
  private readonly apiKey?: string;
  private readonly maxBatchSize: number;
  private readonly maxRetries: number;
  private readonly retryBaseMs: number;
  private readonly fetchImpl: typeof fetch;
  private readonly onError: (error: Error) => void;
  private readonly timer?: ReturnType<typeof setInterval>;
  private queue: CommerceEvent[] = [];
  private inFlight: Promise<void> = Promise.resolve();
  private closed = false;

  constructor(config: OmnirecServerConfig) {
    if (!config?.endpoint || !/^https?:\/\//i.test(config.endpoint)) {
      throw new TypeError("createOmnirecServer: endpoint must be an absolute http(s) URL");
    }
    this.endpoint = config.endpoint.replace(/\/$/, "");
    this.apiKey = config.apiKey;
    this.maxBatchSize = config.maxBatchSize ?? 50;
    this.maxRetries = config.maxRetries ?? 3;
    this.retryBaseMs = config.retryBaseMs ?? 200;
    this.fetchImpl = config.fetchImpl ?? fetch;
    this.onError = config.onError ?? ((error) => console.warn(`[omnirec] ${error.message}`));
    const interval = config.flushIntervalMs ?? 1000;
    if (interval > 0) {
      this.timer = setInterval(() => void this.flush(), interval);
      // Never keep a process alive just to send analytics.
      this.timer.unref?.();
    }
  }

  /** Queues an event. Returns its id. */
  track<E extends keyof KnownEvents & string>(event: E, ...args: TrackArgs<E>): string {
    const [data, options] = args as [Record<string, unknown> | undefined, ServerTrackOptions];
    return this.trackUntyped(event, data ?? {}, options);
  }

  /** Queues an event whose name is only known at runtime. */
  trackUntyped(event: string, data: Record<string, unknown>, options: ServerTrackOptions): string {
    if (this.closed) throw new Error("OmnirecServer is closed");
    const identity = options?.identity ?? {};
    const userId = identity.userId ?? null;
    const anonymousId = identity.anonymousId || (userId ? `user:${userId}` : null);
    if (!anonymousId) {
      throw new TypeError(
        `track("${event}"): identity needs an anonymousId (from identityFromCookies) or a userId`
      );
    }
    const eventId = options.eventId ?? randomUUID();
    this.queue.push({
      eventId,
      event,
      schemaVersion: "2.0",
      source: "server",
      timestamp: options.timestamp ? new Date(options.timestamp).toISOString() : new Date().toISOString(),
      identity: {
        anonymousId,
        userId,
        // A server event may legitimately have no browsing session.
        sessionId: identity.sessionId || `server_${randomUUID()}`,
      },
      context: { platform: "server" },
      data: data as CommerceEvent["data"],
      properties: options.properties ?? {},
    });
    if (this.queue.length >= this.maxBatchSize) void this.flush();
    return eventId;
  }

  /** Links an anonymous visitor to your customer id. */
  identify(identity: ServerIdentity, userId: string, traits?: Record<string, unknown>): string {
    return this.trackUntyped("identify", {}, {
      identity: { ...identity, userId },
      properties: traits ? { traits } : {},
    });
  }

  /** Sends everything queued so far. Resolves once delivered or given up on. */
  flush(): Promise<void> {
    this.inFlight = this.inFlight.then(async () => {
      while (this.queue.length > 0) {
        const batch = this.queue.splice(0, this.maxBatchSize);
        await this.sendWithRetry(batch);
      }
    });
    return this.inFlight;
  }

  /** Flushes and stops the background timer. */
  async close(): Promise<void> {
    if (this.timer) clearInterval(this.timer);
    await this.flush();
    this.closed = true;
  }

  /** Events queued and not yet sent. */
  pending(): number {
    return this.queue.length;
  }

  private async sendWithRetry(batch: CommerceEvent[]): Promise<void> {
    for (let attempt = 0; ; attempt++) {
      let status: number | undefined;
      try {
        const response = await this.fetchImpl(`${this.endpoint}/v1/events/batch`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            ...(this.apiKey ? { "X-Omnirec-Key": this.apiKey } : {}),
          },
          body: JSON.stringify({ events: batch }),
        });
        status = response.status;
        if (status >= 200 && status < 300) return;
        if (status >= 400 && status < 500 && status !== 408 && status !== 429) {
          this.onError(new Error(`collector rejected ${batch.length} event(s) with HTTP ${status}`));
          return;
        }
      } catch {
        // network error: retry
      }
      if (attempt >= this.maxRetries) {
        this.onError(
          new Error(`dropped ${batch.length} event(s) after ${attempt + 1} attempts${status ? ` (HTTP ${status})` : ""}`)
        );
        return;
      }
      const ceiling = this.retryBaseMs * 2 ** attempt;
      await new Promise((resolve) => setTimeout(resolve, Math.random() * ceiling));
    }
  }
}

export function createOmnirecServer(config: OmnirecServerConfig): OmnirecServer {
  return new OmnirecServer(config);
}
