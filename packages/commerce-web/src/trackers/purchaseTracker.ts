import { businessEventId, compact, type EventEmitter } from "../core/emitter";
import type { CommerceItem } from "../events/types";

export interface PurchaseCompletedInput {
  orderId: string;
  items: CommerceItem[];
  total: number;
  currency: string;
  cartId?: string;
  [key: string]: unknown;
}

export interface PurchaseFailedInput {
  orderId: string;
  reason?: string;
  currency?: string;
  total?: number;
  [key: string]: unknown;
}

export interface OrderChangeInput {
  orderId: string;
  items?: CommerceItem[];
  total?: number;
  currency?: string;
  reason?: string;
  [key: string]: unknown;
}

/**
 * Available on the frontend, but **the backend SDK should be your source of
 * truth for all of these**. A browser can't know whether a payment settled, and
 * a confirmation page can be reloaded, bookmarked, or never reached at all.
 *
 * Use this only when no server-side integration point exists. Every method
 * here derives its eventId from the orderId (`evt:<eventType>:<orderId>`),
 * exactly as the backend SDK does, so a purchase reported from both the browser
 * and the server — or retried, or re-fired by a reloaded confirmation page —
 * is delivered once instead of double-counted. See docs/spring-boot-sdk.md.
 */
export class PurchaseTracker {
  constructor(private readonly emitter: EventEmitter) {}

  completed(input: PurchaseCompletedInput): void {
    const { orderId, items, total, currency, cartId, ...rest } = input;
    this.emitter.emit(
      "purchase_completed",
      compact({ orderId, items, total, currency, cartId }),
      compact(rest),
      { eventId: businessEventId("purchase_completed", orderId) }
    );
  }

  failed(input: PurchaseFailedInput): void {
    const { orderId, total, currency, ...rest } = input;
    this.emitter.emit("purchase_failed", compact({ orderId, total, currency }), compact(rest), {
      eventId: businessEventId("purchase_failed", orderId),
    });
  }

  orderCancelled(input: OrderChangeInput): void {
    const { orderId, items, total, currency, ...rest } = input;
    this.emitter.emit("order_cancelled", compact({ orderId, items, total, currency }), compact(rest), {
      eventId: businessEventId("order_cancelled", orderId),
    });
  }

  orderRefunded(input: OrderChangeInput): void {
    const { orderId, items, total, currency, ...rest } = input;
    this.emitter.emit("order_refunded", compact({ orderId, items, total, currency }), compact(rest), {
      eventId: businessEventId("order_refunded", orderId),
    });
  }
}
