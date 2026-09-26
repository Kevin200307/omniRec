// SPDX-License-Identifier: Apache-2.0
/**
 * The canonical, provider-independent commerce event.
 *
 * Mirrored by `io.omnirec.commerce.model.CommerceEvent` on the JVM side and by
 * `schema/commerce-event.schema.json`. All three are kept in sync by
 * `omnirec-contract-tests` — if you add a field here, add it there too or CI fails.
 *
 * Nothing in this file may reference Amazon, Google, or Azure concepts. Provider
 * shapes are produced by mappers behind `EventDestination`, never by the SDK.
 */

/** Bumped only on a breaking change to the event shape. Consumers branch on this. */
export const SCHEMA_VERSION = "1.0";

export type SessionEventType =
  | "session_started"
  | "session_ended"
  | "page_viewed"
  | "home_page_viewed";

export type DiscoveryEventType =
  | "search_performed"
  | "search_result_clicked"
  | "product_list_viewed"
  | "category_viewed"
  | "product_viewed"
  | "product_clicked";

export type ProductInteractionEventType =
  | "product_wishlisted"
  | "product_shared"
  | "product_compared"
  | "product_review_viewed"
  | "product_review_submitted";

export type CartEventType =
  | "cart_viewed"
  | "product_added_to_cart"
  | "product_removed_from_cart"
  | "cart_quantity_updated"
  | "cart_abandoned";

export type CheckoutEventType =
  | "checkout_started"
  | "shipping_information_added"
  | "payment_information_added"
  | "checkout_completed"
  | "checkout_failed";

export type PurchaseEventType =
  | "purchase_completed"
  | "purchase_failed"
  | "order_cancelled"
  | "order_refunded";

export type RecommendationEventType =
  | "recommendation_impression"
  | "recommendation_clicked"
  | "recommendation_added_to_cart"
  | "recommendation_purchased";

export type UserEventType =
  | "user_registered"
  | "user_logged_in"
  | "user_logged_out"
  | "user_profile_updated";

/**
 * Not part of the public taxonomy — an internal control event that establishes
 * the anonymousId -> userId relationship. See docs/identity.md.
 */
export type IdentityEventType = "identify";

export type EventType =
  | SessionEventType
  | DiscoveryEventType
  | ProductInteractionEventType
  | CartEventType
  | CheckoutEventType
  | PurchaseEventType
  | RecommendationEventType
  | UserEventType
  | IdentityEventType;

export const EVENT_TYPES: readonly EventType[] = [
  "session_started",
  "session_ended",
  "page_viewed",
  "home_page_viewed",
  "search_performed",
  "search_result_clicked",
  "product_list_viewed",
  "category_viewed",
  "product_viewed",
  "product_clicked",
  "product_wishlisted",
  "product_shared",
  "product_compared",
  "product_review_viewed",
  "product_review_submitted",
  "cart_viewed",
  "product_added_to_cart",
  "product_removed_from_cart",
  "cart_quantity_updated",
  "cart_abandoned",
  "checkout_started",
  "shipping_information_added",
  "payment_information_added",
  "checkout_completed",
  "checkout_failed",
  "purchase_completed",
  "purchase_failed",
  "order_cancelled",
  "order_refunded",
  "recommendation_impression",
  "recommendation_clicked",
  "recommendation_added_to_cart",
  "recommendation_purchased",
  "user_registered",
  "user_logged_in",
  "user_logged_out",
  "user_profile_updated",
  "identify",
] as const;

/**
 * Who the event belongs to. `anonymousId` is always present; `userId` is present
 * only once the merchant has called identify(). These are never merged in place —
 * historical events keep the anonymousId they were captured with, and the
 * anonymousId -> userId association is carried by a separate identity link.
 */
export interface EventIdentity {
  anonymousId: string;
  userId: string | null;
  sessionId: string;
}

export type Platform = "web" | "ios" | "android" | "server" | "unknown";
export type DeviceType = "desktop" | "mobile" | "tablet" | "unknown";

/**
 * Where the event happened. Collected automatically by the frontend SDK; the
 * Event API overwrites `ip`/`country` from the request itself and never trusts
 * a client-supplied value for them.
 */
export interface EventContext {
  url?: string;
  path?: string;
  referrer?: string;
  platform: Platform;
  device?: DeviceType;
  userAgent?: string;
  locale?: string;
  timezone?: string;
  screenWidth?: number;
  screenHeight?: number;
  /** Server-derived only. A client-supplied value is discarded at ingestion. */
  ip?: string;
  /** Server-derived only. */
  country?: string;
}

export interface CommerceItem {
  productId: string;
  quantity?: number;
  price?: number;
  currency?: string;
  categoryId?: string;
}

/**
 * The commerce payload. Every field is optional here; what is actually required
 * is decided per event type by the EventValidator, because "productId required"
 * is true for product_viewed and meaningless for session_started.
 */
export interface CommerceData {
  productId?: string;
  productIds?: string[];
  categoryId?: string;
  category?: string;
  quantity?: number;
  price?: number;
  currency?: string;
  cartId?: string;
  orderId?: string;
  searchQuery?: string;
  recommendationId?: string;
  /** Who served the recommendation list, e.g. "amazon-personalize". Lets adapters forward attribution only to the provider that issued the id. */
  recommendationProvider?: string;
  listId?: string;
  items?: CommerceItem[];
  total?: number;
}

export interface CommerceEvent {
  eventId: string;
  eventType: EventType;
  schemaVersion: string;
  /** ISO-8601, client capture time. The server records its own receive time separately. */
  timestamp: string;
  identity: EventIdentity;
  context: EventContext;
  commerce: CommerceData;
  /** Free-form merchant attributes. Never put payment credentials here — see docs/security.md. */
  properties: Record<string, unknown>;
}
