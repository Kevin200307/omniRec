import { compact, type EventEmitter } from "../core/emitter";

export interface CartViewedInput {
  cartId: string;
  [key: string]: unknown;
}

export interface CartProductAddedInput {
  productId: string;
  cartId?: string;
  quantity?: number;
  price?: number;
  currency?: string;
  categoryId?: string;
  [key: string]: unknown;
}

export interface CartProductRemovedInput {
  productId: string;
  cartId?: string;
  quantity?: number;
  [key: string]: unknown;
}

export interface CartQuantityUpdatedInput {
  productId: string;
  cartId?: string;
  previousQuantity?: number;
  newQuantity?: number;
  [key: string]: unknown;
}

/**
 * Note what is missing: `abandoned()`. Cart abandonment is a derived event the
 * backend produces from real cart and order state, not something the browser
 * can honestly assert — closing a tab is not abandoning a cart. See
 * docs/event-schema.md#cart-abandonment.
 */
export class CartTracker {
  constructor(private readonly emitter: EventEmitter) {}

  viewed(input: CartViewedInput): void {
    const { cartId, ...rest } = input;
    this.emitter.emit("cart_viewed", { cartId }, compact(rest));
  }

  productAdded(input: CartProductAddedInput): void {
    const { productId, cartId, quantity, price, currency, categoryId, ...rest } = input;
    this.emitter.emit(
      "product_added_to_cart",
      compact({ productId, cartId, quantity: quantity ?? 1, price, currency, categoryId }),
      compact(rest)
    );
  }

  productRemoved(input: CartProductRemovedInput): void {
    const { productId, cartId, quantity, ...rest } = input;
    this.emitter.emit("product_removed_from_cart", compact({ productId, cartId, quantity }), compact(rest));
  }

  /**
   * `newQuantity` becomes the canonical `quantity`; `previousQuantity` stays in
   * properties. That way a consumer reading `commerce.quantity` gets the
   * current truth for every cart event without special-casing this one.
   */
  quantityUpdated(input: CartQuantityUpdatedInput): void {
    const { productId, cartId, previousQuantity, newQuantity, ...rest } = input;
    this.emitter.emit(
      "cart_quantity_updated",
      compact({ productId, cartId, quantity: newQuantity }),
      compact({ previousQuantity, newQuantity, ...rest })
    );
  }
}
