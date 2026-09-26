// SPDX-License-Identifier: Apache-2.0
import type { CommerceEvent } from "../events/types";
import { LocalStore, MemoryStore, type KeyValueStore } from "../storage/storage";

const BUFFER_KEY = "omnirec_offline_buffer";

export interface OfflineBufferOptions {
  /** Hard cap on retained events. Oldest are dropped first once exceeded. */
  maxEvents?: number;
  store?: KeyValueStore;
}

const DEFAULT_MAX_EVENTS = 500;

/**
 * Survives reloads and offline periods so a lost connection doesn't silently
 * lose the visit.
 *
 * Bounded on purpose. An unbounded queue in localStorage is a foot-gun: the
 * 5 MB origin quota is shared with the merchant's own app, so a tab left open
 * offline would grow until *their* writes start throwing. On overflow we drop
 * the oldest events, because for behavioural data the most recent signals are
 * the ones still worth having.
 */
export class OfflineBuffer {
  private readonly store: KeyValueStore;
  private readonly maxEvents: number;

  constructor(options: OfflineBufferOptions = {}) {
    this.maxEvents = options.maxEvents ?? DEFAULT_MAX_EVENTS;
    this.store = options.store ?? (typeof localStorage === "undefined" ? new MemoryStore() : new LocalStore());
  }

  /** Returns the number of events dropped to stay within the cap. */
  append(events: CommerceEvent[]): number {
    if (events.length === 0) return 0;
    const combined = [...this.read(), ...events];
    const overflow = Math.max(0, combined.length - this.maxEvents);
    const retained = overflow > 0 ? combined.slice(overflow) : combined;
    this.write(retained);
    return overflow;
  }

  /** Atomically returns everything buffered and clears the buffer. */
  drain(): CommerceEvent[] {
    const events = this.read();
    if (events.length > 0) this.clear();
    return events;
  }

  size(): number {
    return this.read().length;
  }

  clear(): void {
    this.store.remove(BUFFER_KEY);
  }

  private read(): CommerceEvent[] {
    const raw = this.store.get(BUFFER_KEY);
    if (!raw) return [];
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? (parsed as CommerceEvent[]) : [];
    } catch {
      // Corrupt or half-written entry — discard rather than wedge every flush.
      this.clear();
      return [];
    }
  }

  private write(events: CommerceEvent[]): void {
    try {
      this.store.set(BUFFER_KEY, JSON.stringify(events));
    } catch {
      // Quota exceeded even after capping — drop the buffer rather than throw
      // inside a UI event handler.
      this.clear();
    }
  }
}
