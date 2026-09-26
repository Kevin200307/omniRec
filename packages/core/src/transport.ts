// SPDX-License-Identifier: Apache-2.0
import type { OutgoingEvent } from "./types";

/**
 * Two send paths: a normal batched fetch for regular flushes, and
 * sendBeacon for page-unload flushes (cart abandonment, exit events) where
 * the page may be gone before a fetch would resolve. fetch with
 * keepalive:true is the fallback when sendBeacon is unavailable — it
 * doesn't guarantee delivery on unload the way sendBeacon does, but comes
 * closer than a plain fetch.
 */
export class Transport {
  constructor(private endpoint: string, private tenantId: string) {}

  private url(): string {
    return `${this.endpoint.replace(/\/$/, "")}/v1/events`;
  }

  send(events: OutgoingEvent[]): void {
    if (events.length === 0) return;
    const body = JSON.stringify({ tenantId: this.tenantId, events });

    fetch(this.url(), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
      keepalive: true,
    }).catch(() => {
      // Best-effort: dropped events are preferable to blocking the caller
      // or throwing inside a UI event handler.
    });
  }

  sendBeacon(events: OutgoingEvent[]): void {
    if (events.length === 0) return;
    const body = JSON.stringify({ tenantId: this.tenantId, events });

    if (typeof navigator !== "undefined" && "sendBeacon" in navigator) {
      const blob = new Blob([body], { type: "application/json" });
      const ok = navigator.sendBeacon(this.url(), blob);
      if (ok) return;
    }
    this.send(events);
  }
}
