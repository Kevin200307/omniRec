// SPDX-License-Identifier: Apache-2.0
export {
  OmnirecClient,
  createOmnirec,
  type IdentifyInput,
  type KnownEvents,
  type OmnirecCustomEvents,
  type TrackOptions,
} from "./core/omnirec";
export { runPipeline, type Middleware, type OmnirecPlugin, type PluginHost } from "./core/pipeline";
export { CommerceClient, createCommerceClient } from "./core/client";
export { resolveConfig, type CommerceConfig, type OmnirecConfig, type ResolvedConfig } from "./core/config";
export { toData } from "./compat/v1";
export { businessEventId, compact, type EmitOptions, type EventEmitter } from "./core/emitter";

export {
  EVENT_TYPES,
  SCHEMA_VERSION,
  type SessionEngagementEventType,
  type AcquisitionMessagingEventType,
  type SearchDiscoveryEventType,
  type ProductPageEventType,
  type CartCheckoutEventType,
  type OrdersPaymentsEventType,
  type FulfilmentEventType,
  type SupportEventType,
  type ReturnsRefundsEventType,
  type ReviewsAdvocacyEventType,
  type AccountRetentionEventType,
  type IdentityEventType,
  type CommerceData,
  type CommerceEvent,
  type CommerceItem,
  type DeviceType,
  type EventContext,
  type EventIdentity,
  type EventSource,
  type EventType,
  type EventData,
  type EventDataMap,
  type EventName,
  type EventRule,
  type FieldRule,
  type ProductBlock,
  type CategoryBlock,
  type ListBlock,
  type SearchBlock,
  type CartBlock,
  type OrderBlock,
  type RecommendationBlock,
  type Platform,
} from "./events/types";

export {
  DEFAULT_SESSION_TIMEOUT_MS,
  IdentityManager,
  type IdentityManagerOptions,
  type IdentitySnapshot,
} from "./identity/identityManager";

export { collectContext, detectDevice } from "./context/contextCollector";
export { sanitizeUrl, SENSITIVE_URL_PARAMS } from "./context/sanitizeUrl";
export { DwellTimeTracker, type DwellTimeOptions } from "./dwell/dwellTimeTracker";

export {
  EventValidator,
  SENSITIVE_FIELD_NAMES,
  type ValidationError,
  type ValidationResult,
} from "./validation/validator";

export { Transport, classify, type SendOutcome, type TransportOptions } from "./transport/transport";
export { Batcher, type BatcherOptions } from "./transport/batcher";
export { OfflineBuffer, type OfflineBufferOptions } from "./transport/offlineBuffer";
export { CookieStore, LocalStore, MemoryStore, uuid, type KeyValueStore } from "./storage/storage";

export { SessionTracker } from "./trackers/sessionTracker";
export { HomePageTracker, PageTracker, type PageViewedInput } from "./trackers/pageTracker";
export {
  SearchTracker,
  type SearchPerformedInput,
  type SearchResultClickedInput,
} from "./trackers/searchTracker";
export {
  CategoryTracker,
  ProductListTracker,
  type CategoryViewedInput,
  type ProductListViewedInput,
} from "./trackers/catalogTrackers";
export {
  ProductTracker,
  type ProductClickedInput,
  type ProductReviewSubmittedInput,
  type ProductSharedInput,
  type ProductViewedInput,
} from "./trackers/productTracker";
export {
  CartTracker,
  type CartProductAddedInput,
  type CartProductRemovedInput,
  type CartQuantityUpdatedInput,
  type CartViewedInput,
} from "./trackers/cartTracker";
export {
  CheckoutTracker,
  type CheckoutFailedInput,
  type CheckoutStepInput,
  type PaymentInformationAddedInput,
} from "./trackers/checkoutTracker";
export {
  PurchaseTracker,
  type OrderChangeInput,
  type PurchaseCompletedInput,
  type PurchaseFailedInput,
} from "./trackers/purchaseTracker";
export {
  RecommendationTracker,
  type RecommendationImpressionInput,
  type RecommendationInteractionInput,
} from "./trackers/recommendationTracker";
export { UserTracker, type IdentityActions, type UserInput } from "./trackers/userTracker";
