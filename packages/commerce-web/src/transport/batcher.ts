// SPDX-License-Identifier: Apache-2.0
import type { CommerceEvent } from "../events/types";
import { OfflineBuffer } from "./offlineBuffer";
import type { Transport } from "./transport";

export type DropReason = "rejected" | "overflow" | "expired";

export interface BatcherOptions {
  /** Events per request, and the size that triggers an early flush. Default 20. */
  maxBatchSize?: number;
  /** Flush at least this often, even if the batch isn't full. Default 5s. */
  maxWaitMs?: number;
  /**
   * Events older than this are dropped instead of sent. It must stay below the
   * server's deduplication window (24h by default): an event the server has
   * forgotten can't be recognised as a duplicate, so resending one that did in
   * fact land would double-count it. Default 12 hours.
   */
  maxEventAgeMs?: number;
  /** Longest pause between flush attempts while the API keeps failing. Default 5 minutes. */
  maxBackoffMs?: number;
  offlineBuffer?: OfflineBuffer;
  onDrop?: (events: CommerceEvent[], reason: DropReason) => void;
  autoFlush?: boolean;
  now?: () => number;
}

const DEFAULT_MAX_BATCH_SIZE = 20;
const DEFAULT_MAX_WAIT_MS = 5000;
const DEFAULT_MAX_EVENT_AGE_MS = 12 * 60 * 60 * 1000;
const DEFAULT_MAX_BACKOFF_MS = 5 * 60 * 1000;

/**
 * Buffers events and sends them in batches.
 *
 * - **Chunked.** However much has built up (a long offline spell can leave
 *   hundreds of events), each request carries at most `maxBatchSize`. One
 *   oversized request would be refused by the server as a whole.
 * - **Bounded retry.** A transient failure puts the batch back in the offline
 *   buffer and backs off exponentially between flushes, capped at
 *   `maxBackoffMs` — never a tight loop against a failing API. Coming back
 *   online resets the backoff. The buffer itself is bounded, and events older
 *   than `maxEventAgeMs` are dropped, so retries cannot go on forever.
 * - **Permanent rejection** (4xx) drops that batch and reports it: resending
 *   bytes the server refused cannot succeed.
 * - **Unload-safe.** A batch in the middle of a retry is kept in `inFlight`, so
 *   a page unload during backoff beacons it rather than losing it. If the
 *   original fetch also landed, the server deduplicates on eventId.
 */
export class Batcher {
  private buffer: CommerceEvent[] = [];
  private inFlight: CommerceEvent[] = [];
  private timer: ReturnType<typeof setInterval> | null = null;
  private flushing = false;
  private consecutiveFailures = 0;
  private nextAttemptAt = 0;
  private readonly maxBatchSize: number;
  private readonly maxWaitMs: number;
  private readonly maxEventAgeMs: number;
  private readonly maxBackoffMs: number;
  private readonly offline: OfflineBuffer;
  private readonly onDrop?: BatcherOptions["onDrop"];
  private readonly now: () => number;
  private unloadListeners: Array<() => void> = [];

  constructor(private readonly transport: Transport, options: BatcherOptions = {}) {
    this.maxBatchSize = options.maxBatchSize ?? DEFAULT_MAX_BATCH_SIZE;
    this.maxWaitMs = options.maxWaitMs ?? DEFAULT_MAX_WAIT_MS;
    this.maxEventAgeMs = options.maxEventAgeMs ?? DEFAULT_MAX_EVENT_AGE_MS;
    this.maxBackoffMs = options.maxBackoffMs ?? DEFAULT_MAX_BACKOFF_MS;
    this.offline = options.offlineBuffer ?? new OfflineBuffer();
    this.onDrop = options.onDrop;
    this.now = options.now ?? (() => Date.now());

    if (options.autoFlush ?? true) {
      this.timer = setInterval(() => void this.flush({ force: false }), this.maxWaitMs);
      this.installUnloadHandlers();
    }
  }

  enqueue(event: CommerceEvent): void {
    this.buffer.push(event);
    if (this.buffer.length >= this.maxBatchSize) {
      void this.flush({ force: false });
    }
  }

  pending(): number {
    return this.buffer.length + this.inFlight.length + this.offline.size();
  }

  /** Milliseconds until the next automatic attempt is allowed; 0 when not backing off. */
  backoffRemainingMs(): number {
    return Math.max(0, this.nextAttemptAt - this.now());
  }

  /**
   * Sends everything buffered, including anything left over from a previous
   * failed flush, in chunks of `maxBatchSize`.
   *
   * An explicit call (`force`, the default) ignores backoff; the timer and the
   * batch-size trigger respect it. Overlapping calls return early rather than
   * sending the same events twice.
   */
  async flush(options: { force?: boolean } = {}): Promise<void> {
    const force = options.force ?? true;
    if (this.flushing) return;
    if (!force && this.now() < this.nextAttemptAt) return;

    const queued = this.discardExpired([...this.offline.drain(), ...this.buffer]);
    this.buffer = [];
    if (queued.length === 0) return;

    this.flushing = true;
    this.inFlight = queued;
    try {
      while (this.inFlight.length > 0) {
        const chunk = this.inFlight.slice(0, this.maxBatchSize);
        const outcome = await this.transport.sendWithRetry(chunk);

        if (outcome.status === "retryable") {
          // Keep this chunk and everything after it for a later attempt.
          const dropped = this.offline.append(this.inFlight);
          if (dropped > 0) this.onDrop?.(this.inFlight.slice(0, dropped), "overflow");
          this.inFlight = [];
          this.scheduleBackoff();
          return;
        }
        if (outcome.status === "rejected") {
          this.onDrop?.(chunk, "rejected");
        }
        this.inFlight = this.inFlight.slice(chunk.length);
      }
      this.consecutiveFailures = 0;
      this.nextAttemptAt = 0;
    } finally {
      this.inFlight = [];
      this.flushing = false;
    }
  }

  /**
   * Synchronous best-effort flush for page unload, via sendBeacon in chunks.
   * Anything the beacon can't take goes to the offline buffer for the next page
   * load. Includes a batch that was mid-retry: if its fetch also succeeds, the
   * server drops the second copy by eventId.
   */
  flushOnUnload(): void {
    const queued = this.discardExpired([...this.offline.drain(), ...this.inFlight, ...this.buffer]);
    this.buffer = [];
    if (queued.length === 0) return;

    const unsent: CommerceEvent[] = [];
    for (let from = 0; from < queued.length; from += this.maxBatchSize) {
      const chunk = queued.slice(from, from + this.maxBatchSize);
      if (!this.transport.sendBeacon(chunk)) unsent.push(...chunk);
    }
    if (unsent.length > 0) this.offline.append(unsent);
  }

  destroy(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    for (const remove of this.unloadListeners) remove();
    this.unloadListeners = [];
    this.flushOnUnload();
  }

  private scheduleBackoff(): void {
    this.consecutiveFailures++;
    const exponential = Math.min(this.maxWaitMs * 2 ** (this.consecutiveFailures - 1), this.maxBackoffMs);
    // Full jitter, so a fleet of browsers that failed together doesn't retry together.
    this.nextAttemptAt = this.now() + Math.random() * exponential;
  }

  private discardExpired(events: CommerceEvent[]): CommerceEvent[] {
    const cutoff = this.now() - this.maxEventAgeMs;
    const fresh: CommerceEvent[] = [];
    const expired: CommerceEvent[] = [];
    for (const event of events) {
      const capturedAt = Date.parse(event.timestamp);
      (Number.isFinite(capturedAt) && capturedAt < cutoff ? expired : fresh).push(event);
    }
    if (expired.length > 0) this.onDrop?.(expired, "expired");
    return fresh;
  }

  private installUnloadHandlers(): void {
    if (typeof window === "undefined") return;

    const onPageHide = () => this.flushOnUnload();
    const onVisibility = () => {
      if (typeof document !== "undefined" && document.visibilityState === "hidden") {
        this.flushOnUnload();
      }
    };
    // Back online: the reason for backing off is probably gone.
    const onOnline = () => {
      this.consecutiveFailures = 0;
      this.nextAttemptAt = 0;
      void this.flush({ force: false });
    };

    window.addEventListener("pagehide", onPageHide);
    window.addEventListener("online", onOnline);
    this.unloadListeners.push(() => window.removeEventListener("pagehide", onPageHide));
    this.unloadListeners.push(() => window.removeEventListener("online", onOnline));

    if (typeof document !== "undefined") {
      document.addEventListener("visibilitychange", onVisibility);
      this.unloadListeners.push(() => document.removeEventListener("visibilitychange", onVisibility));
    }
  }
}
