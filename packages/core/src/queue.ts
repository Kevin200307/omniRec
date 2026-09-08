import type { OutgoingEvent } from "./types";
import { Transport } from "./transport";

/**
 * Buffers events and flushes on whichever comes first: batchSize reached,
 * or intervalMs elapsed. flushSync() (beacon path) is used on unload so
 * buffered-but-not-yet-flushed events aren't silently lost when a tab closes.
 */
export class EventQueue {
  private buffer: OutgoingEvent[] = [];
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor(
    private transport: Transport,
    private batchSize: number,
    private intervalMs: number
  ) {
    this.timer = setInterval(() => this.flush(), this.intervalMs);

    if (typeof window !== "undefined") {
      window.addEventListener("pagehide", () => this.flushSync());
      window.addEventListener("beforeunload", () => this.flushSync());
      document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "hidden") this.flushSync();
      });
    }
  }

  enqueue(event: OutgoingEvent): void {
    this.buffer.push(event);
    if (this.buffer.length >= this.batchSize) {
      this.flush();
    }
  }

  flush(): void {
    if (this.buffer.length === 0) return;
    const batch = this.buffer.splice(0, this.buffer.length);
    this.transport.send(batch);
  }

  /** Unload-safe flush — uses sendBeacon so the batch survives tab close. */
  flushSync(): void {
    if (this.buffer.length === 0) return;
    const batch = this.buffer.splice(0, this.buffer.length);
    this.transport.sendBeacon(batch);
  }

  destroy(): void {
    if (this.timer) clearInterval(this.timer);
    this.flushSync();
  }
}
