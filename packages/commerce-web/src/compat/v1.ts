// SPDX-License-Identifier: Apache-2.0
import type { CommerceData } from "../events/types";
import type { EventData } from "../events/generated/catalog";

/**
 * Converts the v1 flat commerce payload to v2 `data` blocks. The rule is the
 * same one the Event API applies to v1 events (Java `V1Compat`), so a helper
 * method and a raw v1 POST produce identical events:
 *
 * - productId, price, quantity -> product
 * - categoryId, category -> category.id, category.name
 * - listId, productIds -> list
 * - searchQuery -> search.query
 * - cartId, orderId -> cart.id, order.id
 * - recommendationId, recommendationProvider -> recommendation
 * - total and items belong to the order when there is an orderId, otherwise to
 *   the cart when there is a cartId, otherwise to the order
 * - currency goes to every block holding an amount
 */
export function toData(c: CommerceData | undefined): EventData {
  const data: Record<string, Record<string, unknown>> = {};
  if (!c) return data;
  const put = (block: string, field: string, value: unknown) => {
    if (value === undefined || value === null) return;
    (data[block] ??= {})[field] = value;
  };

  put("product", "id", c.productId);
  put("product", "price", c.price);
  put("product", "quantity", c.quantity);
  put("category", "id", c.categoryId);
  put("category", "name", c.category);
  put("list", "id", c.listId);
  put("list", "productIds", c.productIds);
  put("search", "query", c.searchQuery);
  put("cart", "id", c.cartId);
  put("order", "id", c.orderId);
  put("recommendation", "id", c.recommendationId);
  put("recommendation", "provider", c.recommendationProvider);

  const owner = c.orderId != null ? "order" : c.cartId != null ? "cart" : "order";
  const hasItems = Array.isArray(c.items) && c.items.length > 0;
  put(owner, "total", c.total);
  if (hasItems) put(owner, "items", c.items);

  if (c.currency != null) {
    if (c.price != null) put("product", "currency", c.currency);
    if (c.total != null || hasItems) put(owner, "currency", c.currency);
    if (c.price == null && c.total == null && !hasItems) {
      put(c.orderId != null ? "order" : c.cartId != null ? "cart" : "product", "currency", c.currency);
    }
  }
  return data as EventData;
}
