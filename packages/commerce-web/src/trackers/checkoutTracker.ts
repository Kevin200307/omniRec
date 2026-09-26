// SPDX-License-Identifier: Apache-2.0
import { compact, type EventEmitter } from "../core/emitter";

export interface CheckoutStepInput {
  cartId: string;
  [key: string]: unknown;
}

export interface PaymentInformationAddedInput {
  cartId: string;
  /**
   * Safe metadata only: "card", "paypal", "apple_pay". Never a card number,
   * CVV, expiry, or token that can be replayed. The validator rejects the
   * event outright if such a field appears anywhere in it.
   */
  paymentMethod?: string;
  [key: string]: unknown;
}

export interface CheckoutFailedInput {
  cartId: string;
  reason?: string;
  errorCode?: string;
  [key: string]: unknown;
}

export class CheckoutTracker {
  constructor(private readonly emitter: EventEmitter) {}

  started(input: CheckoutStepInput): void {
    const { cartId, ...rest } = input;
    this.emitter.emit("checkout_started", { cartId }, compact(rest));
  }

  shippingInformationAdded(input: CheckoutStepInput): void {
    const { cartId, ...rest } = input;
    this.emitter.emit("shipping_information_added", { cartId }, compact(rest));
  }

  paymentInformationAdded(input: PaymentInformationAddedInput): void {
    const { cartId, paymentMethod, ...rest } = input;
    this.emitter.emit("payment_information_added", { cartId }, compact({ paymentMethod, ...rest }));
  }

  completed(input: CheckoutStepInput & { orderId?: string }): void {
    const { cartId, orderId, ...rest } = input;
    this.emitter.emit("checkout_completed", compact({ cartId, orderId }), compact(rest));
  }

  failed(input: CheckoutFailedInput): void {
    const { cartId, ...rest } = input;
    this.emitter.emit("checkout_failed", { cartId }, compact(rest));
  }
}
