// SPDX-License-Identifier: Apache-2.0
import { compact, type EventEmitter } from "../core/emitter";

export interface PageViewedInput {
  /** Defaults to the current location; pass it only to override. */
  url?: string;
  title?: string;
  [key: string]: unknown;
}

/**
 * `url` and `referrer` are already collected into every event's context, so
 * `viewed()` takes no required arguments. SPA routers should call it on each
 * route change — the SDK deliberately does not monkey-patch history.pushState,
 * because silently wrapping a global the host framework also owns causes far
 * subtler bugs than one explicit call per route.
 *
 * A page view is also a navigation, so it ends any product dwell measurement in
 * progress (`onNavigate`). Without that, time spent on the category page after
 * leaving a product would be counted as dwell on the product.
 */
export class PageTracker {
  constructor(
    private readonly emitter: EventEmitter,
    private readonly onNavigate: () => void = () => {}
  ) {}

  viewed(input: PageViewedInput = {}): void {
    this.onNavigate();
    this.emitter.emit("page_viewed", {}, compact(input));
  }
}

export class HomePageTracker {
  constructor(
    private readonly emitter: EventEmitter,
    private readonly onNavigate: () => void = () => {}
  ) {}

  viewed(properties: Record<string, unknown> = {}): void {
    this.onNavigate();
    this.emitter.emit("home_page_viewed", {}, compact(properties));
  }
}
