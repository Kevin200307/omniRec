// SPDX-License-Identifier: Apache-2.0
import type { CommerceEvent } from "../events/types";

export type SendOutcome =
  /** Delivered. Drop the batch. */
  | { status: "delivered" }
  /** Permanently rejected (validation, auth). Retrying cannot help — drop it. */
  | { status: "rejected"; statusCode: number }
  /** Transient (5xx, timeout, offline). Keep the batch and try again later. */
  | { status: "retryable"; statusCode?: number };

export interface TransportOptions {
  endpoint: string;
  /** Public, non-secret key. Never a provider credential — see docs/security.md. */
  apiKey: string;
  tenantId?: string;
  maxRetries?: number;
  /** First backoff step; doubles each attempt, with jitter. */
  retryBaseMs?: number;
  retryMaxMs?: number;
  fetchImpl?: typeof fetch;
}

const DEFAULT_MAX_RETRIES = 3;
const DEFAULT_RETRY_BASE_MS = 500;
const DEFAULT_RETRY_MAX_MS = 15_000;
/** Under the 64KB keepalive body limit, with headroom for multi-byte characters. */
const KEEPALIVE_MAX_BYTES = 60_000;

/**
 * Posts batches to the Event API.
 *
 * Retry policy, per docs/configuration.md:
 *   2xx           -> delivered
 *   408, 429      -> retryable (the server explicitly asked us to back off)
 *   other 4xx     -> rejected; the payload is wrong or the key is bad, and
 *                    resending the identical bytes will fail identically
 *   5xx / network -> retryable with exponential backoff + full jitter
 *
 * Jitter matters more than it looks: without it, every browser that hit the
 * same 503 retries in lockstep and re-creates the spike that caused it.
 */
export class Transport {
  private readonly endpoint: string;
  private readonly apiKey: string;
  private readonly tenantId?: string;
  private readonly maxRetries: number;
  private readonly retryBaseMs: number;
  private readonly retryMaxMs: number;
  private readonly fetchImpl: typeof fetch;

  constructor(options: TransportOptions) {
    this.endpoint = options.endpoint.replace(/\/$/, "");
    this.apiKey = options.apiKey;
    this.tenantId = options.tenantId;
    this.maxRetries = options.maxRetries ?? DEFAULT_MAX_RETRIES;
    this.retryBaseMs = options.retryBaseMs ?? DEFAULT_RETRY_BASE_MS;
    this.retryMaxMs = options.retryMaxMs ?? DEFAULT_RETRY_MAX_MS;
    this.fetchImpl =
      options.fetchImpl ?? (typeof fetch !== "undefined" ? fetch.bind(globalThis) : unavailableFetch);
  }

  batchUrl(): string {
    return `${this.endpoint}/v1/events/batch`;
  }

  /** One attempt, no retry. The Batcher owns the retry loop so it can keep events buffered meanwhile. */
  async send(events: CommerceEvent[]): Promise<SendOutcome> {
    if (events.length === 0) return { status: "delivered" };

    try {
      const body = this.body(events);
      const response = await this.fetchImpl(this.batchUrl(), {
        method: "POST",
        headers: this.headers(),
        body,
        // Browsers refuse keepalive requests whose body exceeds 64KB. Setting it
        // unconditionally turned a large backlog into a "network error" that was
        // retried forever and never delivered.
        keepalive: body.length < KEEPALIVE_MAX_BYTES,
      });
      return classify(response.status);
    } catch {
      // Network failure, DNS, CORS preflight, offline — all retryable.
      return { status: "retryable" };
    }
  }

  /** Retries internally with backoff. Resolves once delivered or permanently given up on. */
  async sendWithRetry(events: CommerceEvent[], sleep = defaultSleep): Promise<SendOutcome> {
    let outcome: SendOutcome = { status: "retryable" };

    for (let attempt = 0; attempt <= this.maxRetries; attempt++) {
      outcome = await this.send(events);
      if (outcome.status !== "retryable") return outcome;
      if (attempt === this.maxRetries) break;
      await sleep(this.backoffMs(attempt));
    }
    return outcome;
  }

  /**
   * Unload-safe path. sendBeacon survives the page going away, which a fetch
   * does not reliably do; it also cannot report success, so there is no retry
   * here by construction.
   */
  sendBeacon(events: CommerceEvent[]): boolean {
    if (events.length === 0) return true;
    if (typeof navigator === "undefined" || typeof navigator.sendBeacon !== "function") {
      return false;
    }
    try {
      // Content-Type must stay text/plain: any other type makes this a
      // non-simple request, and a beacon cannot answer a CORS preflight.
      // The Event API accepts text/plain on the batch endpoint for this reason.
      const blob = new Blob([this.body(events)], { type: "text/plain;charset=UTF-8" });
      return navigator.sendBeacon(this.beaconUrl(), blob);
    } catch {
      return false;
    }
  }

  /**
   * A beacon cannot set headers, so the public key rides as a query parameter
   * on this endpoint only. It is publishable by design — it authorises event
   * writes for one tenant and nothing else.
   */
  private beaconUrl(): string {
    const url = `${this.batchUrl()}?api_key=${encodeURIComponent(this.apiKey)}`;
    return this.tenantId ? `${url}&tenant_id=${encodeURIComponent(this.tenantId)}` : url;
  }

  backoffMs(attempt: number): number {
    const exponential = Math.min(this.retryBaseMs * 2 ** attempt, this.retryMaxMs);
    return Math.random() * exponential;
  }

  private headers(): Record<string, string> {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      "X-Omnirec-Key": this.apiKey,
    };
    if (this.tenantId) headers["X-Omnirec-Tenant"] = this.tenantId;
    return headers;
  }

  private body(events: CommerceEvent[]): string {
    return JSON.stringify({ tenantId: this.tenantId, events });
  }
}

export function classify(statusCode: number): SendOutcome {
  if (statusCode >= 200 && statusCode < 300) return { status: "delivered" };
  if (statusCode === 408 || statusCode === 429) return { status: "retryable", statusCode };
  if (statusCode >= 400 && statusCode < 500) return { status: "rejected", statusCode };
  return { status: "retryable", statusCode };
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function unavailableFetch(): Promise<Response> {
  return Promise.reject(new Error("fetch is not available in this environment"));
}
