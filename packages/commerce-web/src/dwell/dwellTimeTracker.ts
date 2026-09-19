import type { CommerceData } from "../events/types";

export type DwellFlushHandler = (
  productId: string,
  dwellTimeMs: number,
  commerce: CommerceData,
  viewEventId: string | null
) => void;

export interface DwellTimeOptions {
  /** Below this, treat it as a bounce rather than engagement. Default 1000ms. */
  minDwellMs?: number;
  /**
   * Above this, stop accumulating. A tab left open overnight is not 8 hours of
   * engagement, and an outlier like that poisons any average built on it.
   * Default 30 minutes.
   */
  maxDwellMs?: number;
  now?: () => number;
}

const DEFAULT_MIN_DWELL_MS = 1000;
const DEFAULT_MAX_DWELL_MS = 30 * 60 * 1000;

/**
 * Measures how long a visitor actually spent on a product.
 *
 * ## How it works
 * `product.viewed()` starts a timer. The timer pauses when the tab is hidden
 * (Page Visibility API) and resumes when it comes back, so only foreground time
 * counts. When the visitor moves to another product, the page unloads, or
 * `flush()` is called, the accumulated total is emitted as a second
 * `product_viewed` event carrying `dwellTimeMs`.
 *
 * ## What it cannot tell you
 * - **Attention, only presence.** A focused tab the visitor isn't looking at
 *   still accrues time. There is no browser API for eyes-on-screen.
 * - **Nothing on a hard crash.** If the browser or tab dies, the in-flight
 *   measurement is lost; only completed ones reach the buffer.
 * - **Truncated at `maxDwellMs`**, so a genuine long read is recorded as
 *   exactly 30 minutes. Treat the cap as a censored value, not a real one.
 * - **Deliberately not heartbeats.** Emitting every few seconds would give
 *   crash resilience at the cost of flooding the pipeline, so we don't.
 *
 * Consumers therefore get two `product_viewed` events for one product view: the
 * view itself, then on exit an **engagement update** carrying `dwellTimeMs` and
 * `viewEventId` (the eventId of the view it measures). The update is not a
 * second view: interaction-counting destinations (Personalize, Retail) skip
 * any event with `viewEventId`, so views are never double-counted, while an
 * analytics destination can join the two on it.
 *
 * ## When measurement ends
 * - another product is viewed;
 * - `page.viewed()` / `home.viewed()` is called (an SPA route change);
 * - `product.viewEnded()` is called (a component unmounting — see
 *   `useProductView` in @omnirec/commerce-react);
 * - the page is hidden-then-unloaded (`pagehide`).
 *
 * ## Lifecycle limits
 * - **Background tabs** — paused, so time behind another tab isn't counted.
 * - **Browser termination / crash** — the in-flight measurement is lost.
 * - **Mobile** — iOS and Android may freeze a backgrounded page without firing
 *   `pagehide`; the pause on `visibilitychange` still stops the clock, but the
 *   update is only sent if the page later resumes or unloads normally.
 * - **Refresh** — `pagehide` fires first, so the measurement is flushed.
 */
export class DwellTimeTracker {
  private productId: string | null = null;
  private commerce: CommerceData = {};
  private viewId: string | null = null;
  private accumulatedMs = 0;
  private startedAt: number | null = null;
  private readonly minDwellMs: number;
  private readonly maxDwellMs: number;
  private readonly now: () => number;
  private detachListeners: Array<() => void> = [];

  constructor(
    private readonly onFlush: DwellFlushHandler,
    options: DwellTimeOptions = {}
  ) {
    this.minDwellMs = options.minDwellMs ?? DEFAULT_MIN_DWELL_MS;
    this.maxDwellMs = options.maxDwellMs ?? DEFAULT_MAX_DWELL_MS;
    this.now = options.now ?? (() => Date.now());
    this.attachLifecycleListeners();
  }

  /** Begins (or restarts) measurement. Flushes any product already being measured. */
  start(productId: string, commerce: CommerceData = {}, viewId?: string): void {
    if (this.productId && this.productId !== productId) {
      this.flush();
    }
    if (this.productId === productId) {
      // Same product viewed again without leaving — keep accumulating.
      if (this.startedAt === null) this.startedAt = this.now();
      return;
    }
    this.productId = productId;
    this.commerce = commerce;
    this.viewId = viewId ?? null;
    this.accumulatedMs = 0;
    this.startedAt = this.isVisible() ? this.now() : null;
  }

  pause(): void {
    if (this.startedAt === null) return;
    this.accumulatedMs += this.now() - this.startedAt;
    this.startedAt = null;
  }

  resume(): void {
    if (this.productId === null || this.startedAt !== null) return;
    this.startedAt = this.now();
  }

  /** Emits the accumulated dwell time, if it clears `minDwellMs`, and stops measuring. */
  flush(): void {
    const productId = this.productId;
    if (productId === null) return;

    this.pause();
    const dwellTimeMs = Math.min(this.accumulatedMs, this.maxDwellMs);
    const commerce = this.commerce;
    const viewId = this.viewId;

    this.productId = null;
    this.commerce = {};
    this.viewId = null;
    this.accumulatedMs = 0;
    this.startedAt = null;

    if (dwellTimeMs < this.minDwellMs) return;
    this.onFlush(productId, dwellTimeMs, commerce, viewId);
  }

  currentProductId(): string | null {
    return this.productId;
  }

  destroy(): void {
    this.flush();
    for (const detach of this.detachListeners) detach();
    this.detachListeners = [];
  }

  private isVisible(): boolean {
    return typeof document === "undefined" || document.visibilityState === "visible";
  }

  private attachLifecycleListeners(): void {
    if (typeof document === "undefined") return;

    const onVisibility = () => {
      if (document.visibilityState === "hidden") this.pause();
      else this.resume();
    };
    document.addEventListener("visibilitychange", onVisibility);
    this.detachListeners.push(() => document.removeEventListener("visibilitychange", onVisibility));

    if (typeof window !== "undefined") {
      const onPageHide = () => this.flush();
      window.addEventListener("pagehide", onPageHide);
      this.detachListeners.push(() => window.removeEventListener("pagehide", onPageHide));
    }
  }
}
