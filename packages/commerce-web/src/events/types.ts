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

import { EVENT_NAMES, type EventData, type EventName } from "./generated/catalog";

/** Bumped only on a breaking change to the event shape. Consumers branch on this. */
export const SCHEMA_VERSION = "2.0";

/**
 * Event names come from the catalog (`catalog/events`), generated into
 * `./generated/catalog.ts` by `npm run catalog:generate`. Edit the YAML, never
 * the generated file. The per-domain unions keep the names this module has
 * always exported.
 */
export type {
  SessionEngagementEventType,
  AcquisitionMessagingEventType,
  SearchDiscoveryEventType,
  ProductPageEventType,
  CartCheckoutEventType,
  OrdersPaymentsEventType,
  FulfilmentEventType,
  SupportEventType,
  ReturnsRefundsEventType,
  ReviewsAdvocacyEventType,
  AccountRetentionEventType,
  IdentityEventType,
  EventDomain,
  EventData,
  EventDataMap,
  EventName,
  EventRule,
  FieldRule,
  ProductBlock,
  CategoryBlock,
  ListBlock,
  SearchBlock,
  CartBlock,
  OrderBlock,
  RecommendationBlock,
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
  /** Marketing attribution from the landing URL, persisted for the session. */
  campaign?: {
    source?: string;
    medium?: string;
    name?: string;
    term?: string;
    content?: string;
    clickId?: string;
    clickIdType?: "gclid" | "fbclid" | "msclkid" | "ttclid";
  };
  /** Page kind the storefront declared, for example product or cart. */
  page?: { type?: string; title?: string };
}

export interface CommerceItem {
  productId: string;
  quantity?: number;
  price?: number;
  currency?: string;
  categoryId?: string;
}

/**
 * The v1 flat commerce payload. Still accepted by the per-event helper methods
 * (`commerce.cart.productAdded(...)`), which convert it to v2 blocks with
 * `toData()`. New code passes v2 `data` to `track()` instead.
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

export type EventSource = "browser" | "server" | "webhook" | "derived" | "import";

/** An event as the SDK sends it: envelope v2. */
export interface CommerceEvent {
  eventId: string;
  /** A catalog name, or a custom name from the tracking plan. */
  event: string;
  schemaVersion: string;
  source: EventSource;
  /** ISO-8601, client capture time. The server records its own receive time separately. */
  timestamp: string;
  identity: EventIdentity;
  context: EventContext;
  /** Catalog blocks (product, cart, order, ...) and plan-declared fields. */
  data: EventData;
  /** Free-form merchant attributes. Never put payment credentials here — see docs/security.md. */
  properties: Record<string, unknown>;
}
