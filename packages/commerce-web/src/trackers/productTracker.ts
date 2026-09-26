// SPDX-License-Identifier: Apache-2.0
import { compact, type EventEmitter } from "../core/emitter";
import type { DwellTimeTracker } from "../dwell/dwellTimeTracker";

export interface ProductViewedInput {
  productId: string;
  categoryId?: string;
  category?: string;
  price?: number;
  currency?: string;
  [key: string]: unknown;
}

export interface ProductClickedInput {
  productId: string;
  categoryId?: string;
  position?: number;
  [key: string]: unknown;
}

export interface ProductSharedInput {
  productId: string;
  /** e.g. "whatsapp", "email", "copy_link". */
  method?: string;
  [key: string]: unknown;
}

export interface ProductReviewSubmittedInput {
  productId: string;
  rating?: number;
  reviewId?: string;
  [key: string]: unknown;
}

/**
 * `viewed()` also opens a dwell-time measurement for the product. The
 * accumulated time is attached to a *later* `product_viewed` event when the
 * visitor leaves — see DwellTimeTracker for why it works that way and what it
 * cannot measure.
 */
export class ProductTracker {
  constructor(
    private readonly emitter: EventEmitter,
    private readonly dwell?: DwellTimeTracker
  ) {}

  viewed(input: ProductViewedInput): void {
    const { productId, categoryId, category, price, currency, ...rest } = input;
    const viewEventId = this.emitter.emit(
      "product_viewed",
      compact({ productId, categoryId, category, price, currency }),
      compact(rest)
    );
    // Only measure a view that was actually recorded: an engagement update
    // pointing at a rejected view would reference nothing.
    if (viewEventId) {
      this.dwell?.start(productId, compact({ categoryId, category, price, currency }), viewEventId);
    }
  }

  /**
   * Ends dwell measurement for the product currently being viewed — call it
   * when a product component unmounts without a full page change. Safe to call
   * when nothing is being measured.
   */
  viewEnded(): void {
    this.dwell?.flush();
  }

  clicked(input: ProductClickedInput): void {
    const { productId, categoryId, position, ...rest } = input;
    this.emitter.emit("product_clicked", compact({ productId, categoryId }), compact({ position, ...rest }));
  }

  wishlisted(input: { productId: string } & Record<string, unknown>): void {
    const { productId, ...rest } = input;
    this.emitter.emit("product_wishlisted", { productId }, compact(rest));
  }

  shared(input: ProductSharedInput): void {
    const { productId, method, ...rest } = input;
    this.emitter.emit("product_shared", { productId }, compact({ method, ...rest }));
  }

  compared(input: { productId: string; comparedWith?: string[] } & Record<string, unknown>): void {
    const { productId, ...rest } = input;
    this.emitter.emit("product_compared", { productId }, compact(rest));
  }

  reviewViewed(input: { productId: string; reviewId?: string } & Record<string, unknown>): void {
    const { productId, ...rest } = input;
    this.emitter.emit("product_review_viewed", { productId }, compact(rest));
  }

  /**
   * Present for parity, but the backend SDK is the better source: only the
   * server knows whether a review survived moderation. See docs/spring-boot-sdk.md.
   */
  reviewSubmitted(input: ProductReviewSubmittedInput): void {
    const { productId, ...rest } = input;
    this.emitter.emit("product_review_submitted", { productId }, compact(rest));
  }
}
