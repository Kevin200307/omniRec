// SPDX-License-Identifier: Apache-2.0
"use client";

import { useEffect } from "react";
import type { ProductViewedInput } from "@omnirec/commerce-web";
import { useCommerce } from "./OmnirecProvider";

/**
 * Tracks a product view for as long as the component is mounted:
 * `product_viewed` on mount (and whenever `productId` changes), and the dwell
 * measurement ends on unmount.
 *
 * Without this, a product page unmounted by client-side navigation would keep
 * its dwell timer running until the next product view, counting time spent on
 * other pages as time on this product.
 *
 *     function ProductPage({ product }) {
 *       useProductView({ productId: product.id, price: product.price, currency: "USD" });
 *       ...
 *     }
 */
export function useProductView(input: ProductViewedInput): void {
  const commerce = useCommerce();
  const { productId } = input;

  useEffect(() => {
    commerce.product.viewed(input);
    return () => commerce.product.viewEnded();
    // Re-run only when the product changes, not on every render's new object.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [commerce, productId]);
}
