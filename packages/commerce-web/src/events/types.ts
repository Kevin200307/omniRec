// SPDX-License-Identifier: Apache-2.0
/**
 * The canonical, provider-independent commerce event.
 *
 * Mirrored by `io.omnirec.commerce.model.CommerceEvent` on the JVM side and by
 * `schema/commerce-event.schema.json`. Event names are generated from the
 * catalog; envelope fields are still kept in sync by `omnirec-contract-tests`,
 * so if you add a field here, add it there too or CI fails.
 *
 * Nothing in this file may reference Amazon, Google, or Azure concepts. Provider
 * shapes are produced by mappers behind `EventDestination`, never by the SDK.
 */

import { EVENT_NAMES, type EventName } from "./generated/catalog";

/** Bumped only on a breaking change to the event shape. Consumers branch on this. */
export const SCHEMA_VERSION = "1.0";

/**
 * Event names come from the catalog (`catalog/events`), generated into
 * `./generated/catalog.ts` by `npm run catalog:generate`. Edit the YAML, never
 * the generated file. The per-domain unions keep the names this module has
 * always exported.
 */
export type {
  CartEventType,
  CheckoutEventType,
  DiscoveryEventType,
  EventDomain,
  IdentityEventType,
  ProductInteractionEventType,
  PurchaseEventType,
  RecommendationEventType,
  SessionEventType,
  UserEventType,
} from "./generated/catalog";

export type EventType = EventName;

export const EVENT_TYPES: readonly EventType[] = EVENT_NAMES;

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
